package com.wildan.adzancountdown

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

data class City(val id: String, val name: String)

/**
 * Official Kemenag RI prayer schedule for every Indonesian kabupaten/kota, from the
 * public myQuran API (https://api.myquran.com/v2):
 *
 *  - `GET /sholat/kota/semua`                   → all cities `[{id, lokasi}]`
 *  - `GET /sholat/jadwal/{cityId}/{yyyy}/{mm}`   → whole month `{daerah, jadwal:[{date, subuh, ...}]}`
 *
 * Responses are cached in app storage, so alarms keep working offline. We keep the
 * current and next month for the chosen city. Times in the API are local wall-clock
 * time of the city (WIB / WITA / WIT); [zoneForProvince] converts them to instants.
 */
class KemenagRepository(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "kemenag").apply { mkdirs() }
    private val citiesFile = File(dir, "cities.json")

    // ---- Cities -------------------------------------------------------------

    fun cachedCities(): List<City>? =
        citiesFile.takeIf { it.exists() }?.let { runCatching { parseCities(it.readText()) }.getOrNull() }

    /** Blocking network call. */
    @Throws(IOException::class)
    fun fetchCities(): List<City> {
        val body = get("$BASE/sholat/kota/semua")
        val cities = parseCities(body)
        if (cities.isEmpty()) throw IOException("Empty city list")
        writeAtomically(citiesFile, body)
        return cities
    }

    // ---- Schedules ----------------------------------------------------------

    /** Prayer times for [date] (the city's local date), or null if not downloaded yet. */
    fun timesFor(cityId: String, date: LocalDate): Map<Prayer, Instant>? {
        if (!isValidCityId(cityId)) return null
        val month = loadMonth(cityId, YearMonth.from(date)) ?: return null
        return month.days[date]
    }

    fun hasMonth(cityId: String, month: YearMonth) = isValidCityId(cityId) && monthFile(cityId, month).exists()

    /**
     * Downloads the current and next month for [cityId] if they are missing.
     * Blocking network call; returns true if everything needed is cached afterwards.
     */
    fun ensureCached(cityId: String, today: LocalDate = LocalDate.now()): Boolean {
        if (!isValidCityId(cityId)) return false
        val needed = listOf(YearMonth.from(today), YearMonth.from(today).plusMonths(1))
        var ok = true
        for (m in needed) {
            if (hasMonth(cityId, m)) continue
            try {
                val body = get("$BASE/sholat/jadwal/$cityId/${m.year}/${"%02d".format(m.monthValue)}")
                parseMonth(body, expectedCityId = cityId, expectedMonth = m) // validate before caching
                writeAtomically(monthFile(cityId, m), body)
            } catch (e: Exception) {
                ok = false
            }
        }
        pruneOldMonths(YearMonth.from(today).minusMonths(1))
        return ok
    }

    private fun loadMonth(cityId: String, month: YearMonth): ParsedMonth? {
        val f = monthFile(cityId, month)
        if (!f.exists()) return null
        return runCatching { parseMonth(f.readText(), cityId, month) }.getOrNull()
    }

    /** Only called with ids that passed [isValidCityId] (digits only), so no path traversal. */
    private fun monthFile(cityId: String, month: YearMonth): File {
        require(isValidCityId(cityId))
        return File(dir, "jadwal_${cityId}_$month.json")
    }

    /** Write to a temp file and rename, so a crash mid-write never leaves a corrupt cache. */
    private fun writeAtomically(target: File, content: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Could not write ${target.name}")
        }
    }

    private fun pruneOldMonths(keepFrom: YearMonth) {
        dir.listFiles { f -> f.name.startsWith("jadwal_") }?.forEach { f ->
            val ym = f.name.removeSuffix(".json").substringAfterLast('_')
            if (runCatching { YearMonth.parse(ym) }.getOrNull()?.isBefore(keepFrom) == true) f.delete()
        }
    }

    private fun get(url: String): String {
        val u = URL(url)
        // Defence in depth (cleartext is also blocked by network_security_config.xml).
        if (u.protocol != "https" || u.host != API_HOST) throw IOException("Refusing non-API URL")
        val conn = u.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = false // never get bounced to another host
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "AdzanCountdown-Android/1.0")
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return readLimited(conn.inputStream.buffered(), MAX_RESPONSE_BYTES)
        } finally {
            conn.disconnect()
        }
    }

    data class ParsedMonth(val province: String, val zone: ZoneId, val days: Map<LocalDate, Map<Prayer, Instant>>)

    companion object {
        private const val API_HOST = "api.myquran.com"
        private const val BASE = "https://$API_HOST/v2"

        /** The whole city list is ~25 KB and a month ~6 KB; anything near this is bogus. */
        private const val MAX_RESPONSE_BYTES = 512 * 1024
        private const val MAX_CITY_NAME_LENGTH = 80
        private val CITY_ID = Regex("^[0-9]{1,6}$")

        /** City ids are used in file names and URLs, so only short digit strings are accepted. */
        fun isValidCityId(id: String) = CITY_ID.matches(id)

        fun readLimited(input: java.io.InputStream, maxBytes: Int): String {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            input.use {
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    if (out.size() + n > maxBytes) throw IOException("Response too large")
                    out.write(buf, 0, n)
                }
            }
            return out.toString(Charsets.UTF_8.name())
        }

        private val WITA = setOf(
            "BALI", "NUSA TENGGARA BARAT", "NUSA TENGGARA TIMUR",
            "KALIMANTAN SELATAN", "KALIMANTAN TIMUR", "KALIMANTAN UTARA", "GORONTALO",
        )

        /** Indonesian time zone of a province as named by the API ("daerah"). */
        fun zoneForProvince(province: String): ZoneId {
            val p = province.uppercase().trim()
            return when {
                p.startsWith("PAPUA") || p.startsWith("MALUKU") -> ZoneId.of("Asia/Jayapura") // WIT
                p in WITA || p.startsWith("SULAWESI") -> ZoneId.of("Asia/Makassar")            // WITA
                else -> ZoneId.of("Asia/Jakarta")                                              // WIB
            }
        }

        fun parseCities(body: String): List<City> {
            val root = JSONObject(body)
            if (!root.optBoolean("status")) throw IOException("API returned status=false")
            val arr = root.getJSONArray("data")
            return (0 until arr.length()).mapNotNull {
                val o = arr.optJSONObject(it) ?: return@mapNotNull null
                val id = o.optString("id")
                // Strip control characters; skip anything that doesn't look like a real entry.
                val name = o.optString("lokasi").filter { ch -> !ch.isISOControl() }.trim()
                if (!isValidCityId(id) || name.isEmpty() || name.length > MAX_CITY_NAME_LENGTH) {
                    null
                } else {
                    City(id, name)
                }
            }.distinctBy { it.id }.sortedBy { it.name }
        }

        /**
         * Parses and validates one month. With [expectedCityId] / [expectedMonth], a response
         * for a different city or month is rejected instead of being cached under the wrong key.
         */
        fun parseMonth(
            body: String,
            expectedCityId: String? = null,
            expectedMonth: YearMonth? = null,
        ): ParsedMonth {
            val root = JSONObject(body)
            if (!root.optBoolean("status")) throw IOException("API returned status=false")
            val data = root.getJSONObject("data")
            if (expectedCityId != null && data.optString("id").trimStart('0') != expectedCityId.trimStart('0')) {
                throw IOException("Schedule is for a different city")
            }
            val province = data.optString("daerah")
            val zone = zoneForProvince(province)
            val jadwal: JSONArray = data.getJSONArray("jadwal")
            val days = (0 until jadwal.length()).associate { i ->
                val d = jadwal.getJSONObject(i)
                val date = LocalDate.parse(d.getString("date"))
                if (expectedMonth != null && YearMonth.from(date) != expectedMonth) {
                    throw IOException("Schedule contains a date outside the requested month")
                }
                fun at(key: String) = date.atTime(LocalTime.parse(d.getString(key))).atZone(zone).toInstant()
                val times = mapOf(
                    Prayer.FAJR to at("subuh"),
                    Prayer.DHUHR to at("dzuhur"),
                    Prayer.ASR to at("ashar"),
                    Prayer.MAGHRIB to at("maghrib"),
                    Prayer.ISHA to at("isya"),
                )
                // Garbled data guard: the five prayers must come in order within the day.
                val ordered = Prayer.entries.map { times.getValue(it) }
                if (ordered.zipWithNext().any { (a, b) -> !a.isBefore(b) }) {
                    throw IOException("Prayer times out of order on $date")
                }
                date to times
            }
            if (days.isEmpty()) throw IOException("Empty schedule")
            if (expectedMonth != null && days.size != expectedMonth.lengthOfMonth()) {
                throw IOException("Incomplete month")
            }
            return ParsedMonth(province, zone, days)
        }
    }
}
