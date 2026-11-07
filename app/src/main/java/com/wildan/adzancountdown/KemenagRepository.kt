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
        citiesFile.writeText(body)
        return cities
    }

    // ---- Schedules ----------------------------------------------------------

    /** Prayer times for [date] (the city's local date), or null if not downloaded yet. */
    fun timesFor(cityId: String, date: LocalDate): Map<Prayer, Instant>? {
        val month = loadMonth(cityId, YearMonth.from(date)) ?: return null
        return month.days[date]
    }

    fun hasMonth(cityId: String, month: YearMonth) = monthFile(cityId, month).exists()

    /**
     * Downloads the current and next month for [cityId] if they are missing.
     * Blocking network call; returns true if everything needed is cached afterwards.
     */
    fun ensureCached(cityId: String, today: LocalDate = LocalDate.now()): Boolean {
        val needed = listOf(YearMonth.from(today), YearMonth.from(today).plusMonths(1))
        var ok = true
        for (m in needed) {
            if (hasMonth(cityId, m)) continue
            try {
                val body = get("$BASE/sholat/jadwal/$cityId/${m.year}/${"%02d".format(m.monthValue)}")
                parseMonth(body) // validate before caching
                monthFile(cityId, m).writeText(body)
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
        return runCatching { parseMonth(f.readText()) }.getOrNull()
    }

    private fun monthFile(cityId: String, month: YearMonth) = File(dir, "jadwal_${cityId}_$month.json")

    private fun pruneOldMonths(keepFrom: YearMonth) {
        dir.listFiles { f -> f.name.startsWith("jadwal_") }?.forEach { f ->
            val ym = f.name.removeSuffix(".json").substringAfterLast('_')
            if (runCatching { YearMonth.parse(ym) }.getOrNull()?.isBefore(keepFrom) == true) f.delete()
        }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "AdzanCountdown-Android/1.0")
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    data class ParsedMonth(val province: String, val zone: ZoneId, val days: Map<LocalDate, Map<Prayer, Instant>>)

    companion object {
        private const val BASE = "https://api.myquran.com/v2"

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
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                City(o.getString("id"), o.getString("lokasi"))
            }.sortedBy { it.name }
        }

        fun parseMonth(body: String): ParsedMonth {
            val root = JSONObject(body)
            if (!root.optBoolean("status")) throw IOException("API returned status=false")
            val data = root.getJSONObject("data")
            val province = data.optString("daerah")
            val zone = zoneForProvince(province)
            val jadwal: JSONArray = data.getJSONArray("jadwal")
            val days = (0 until jadwal.length()).associate { i ->
                val d = jadwal.getJSONObject(i)
                val date = LocalDate.parse(d.getString("date"))
                fun at(key: String) = date.atTime(LocalTime.parse(d.getString(key))).atZone(zone).toInstant()
                date to mapOf(
                    Prayer.FAJR to at("subuh"),
                    Prayer.DHUHR to at("dzuhur"),
                    Prayer.ASR to at("ashar"),
                    Prayer.MAGHRIB to at("maghrib"),
                    Prayer.ISHA to at("isya"),
                )
            }
            if (days.isEmpty()) throw IOException("Empty schedule")
            return ParsedMonth(province, zone, days)
        }
    }
}
