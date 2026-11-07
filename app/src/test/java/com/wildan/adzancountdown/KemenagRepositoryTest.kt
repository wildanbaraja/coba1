package com.wildan.adzancountdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.YearMonth
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class KemenagRepositoryTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test
    fun provincesMapToCorrectIndonesianTimeZones() {
        val wib = ZoneId.of("Asia/Jakarta")
        val wita = ZoneId.of("Asia/Makassar")
        val wit = ZoneId.of("Asia/Jayapura")
        mapOf(
            "DKI JAKARTA" to wib, "JAWA BARAT" to wib, "ACEH" to wib, "KALIMANTAN BARAT" to wib,
            "KALIMANTAN TENGAH" to wib, "KEPULAUAN BANGKA BELITUNG" to wib,
            "BALI" to wita, "NUSA TENGGARA BARAT" to wita, "NUSA TENGGARA TIMUR" to wita,
            "KALIMANTAN SELATAN" to wita, "KALIMANTAN TIMUR" to wita, "KALIMANTAN UTARA" to wita,
            "GORONTALO" to wita, "SULAWESI SELATAN" to wita, "SULAWESI BARAT" to wita,
            "MALUKU" to wit, "MALUKU UTARA" to wit, "PAPUA" to wit, "PAPUA BARAT" to wit,
        ).forEach { (province, zone) ->
            assertEquals(province, zone, KemenagRepository.zoneForProvince(province))
        }
    }

    @Test
    fun parsesMonthlyScheduleInCityLocalTime() {
        // Real response for KOTA MAKASSAR (WITA, UTC+8), November 2026.
        val month = KemenagRepository.parseMonth(resource("jadwal_makassar_2026-11.json"))
        assertEquals("SULAWESI SELATAN", month.province)
        assertEquals(30, month.days.size)

        val day = month.days.getValue(LocalDate.of(2026, 11, 7))
        val wita = ZoneId.of("Asia/Makassar")
        fun local(p: Prayer) = day.getValue(p).atZone(wita).toLocalTime()
        assertEquals(LocalTime.parse("04:18"), local(Prayer.FAJR))
        assertEquals(LocalTime.parse("11:49"), local(Prayer.DHUHR))
        assertEquals(LocalTime.parse("15:08"), local(Prayer.ASR))
        assertEquals(LocalTime.parse("17:59"), local(Prayer.MAGHRIB))
        assertEquals(LocalTime.parse("19:10"), local(Prayer.ISHA))
    }

    @Test
    fun parsesCityList() {
        val body = """{"status":true,"data":[{"id":"1219","lokasi":"KOTA BANDUNG"},{"id":"1201","lokasi":"KAB. BANDUNG"}]}"""
        val cities = KemenagRepository.parseCities(body)
        assertEquals(listOf(City("1201", "KAB. BANDUNG"), City("1219", "KOTA BANDUNG")), cities)
    }

    // ---- Security / validation ---------------------------------------------

    @Test
    fun cityIdsMustBeShortDigitStrings() {
        assertTrue(KemenagRepository.isValidCityId("1301"))
        assertTrue(KemenagRepository.isValidCityId("0101"))
        listOf("", "../../shared_prefs/x", "13/01", "1301.json", "abc", "1234567", "13 01", "-1")
            .forEach { assertFalse(it, KemenagRepository.isValidCityId(it)) }
    }

    @Test
    fun cityListDropsMaliciousOrMalformedEntries() {
        val body = """{"status":true,"data":[
            {"id":"../../shared_prefs/adzan_settings","lokasi":"EVIL"},
            {"id":"1219","lokasi":"KOTA\u0000 BANDUNG"},
            {"id":"9999","lokasi":""},
            {"id":"8888","lokasi":"${"X".repeat(200)}"},
            {"id":"1219","lokasi":"DUPLICATE"}
        ]}"""
        assertEquals(listOf(City("1219", "KOTA BANDUNG")), KemenagRepository.parseCities(body))
    }

    @Test
    fun rejectsScheduleForWrongCityOrMonth() {
        val body = resource("jadwal_makassar_2026-11.json")
        // Correct expectations parse fine.
        KemenagRepository.parseMonth(body, expectedCityId = "2622", expectedMonth = YearMonth.of(2026, 11))
        assertThrows(IOException::class.java) {
            KemenagRepository.parseMonth(body, expectedCityId = "1301")
        }
        assertThrows(IOException::class.java) {
            KemenagRepository.parseMonth(body, expectedMonth = YearMonth.of(2026, 12))
        }
    }

    @Test
    fun rejectsOutOfOrderTimes() {
        val body = """{"status":true,"data":{"id":1301,"daerah":"DKI JAKARTA","jadwal":[
            {"date":"2026-11-07","subuh":"04:07","dzuhur":"11:40","ashar":"23:59","maghrib":"17:50","isya":"19:02"}
        ]}}"""
        assertThrows(IOException::class.java) { KemenagRepository.parseMonth(body) }
    }

    @Test
    fun responseSizeIsCapped() {
        val big = ByteArray(2048) { 'a'.code.toByte() }
        assertThrows(IOException::class.java) {
            KemenagRepository.readLimited(ByteArrayInputStream(big), 1024)
        }
        assertEquals(2048, KemenagRepository.readLimited(ByteArrayInputStream(big), 4096).length)
    }
}
