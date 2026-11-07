package com.wildan.adzancountdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

class PrayerTimeCalculatorTest {
    private val jakarta = ZoneId.of("Asia/Jakarta")

    private fun local(times: Map<Prayer, java.time.Instant>, p: Prayer): LocalTime =
        times.getValue(p).atZone(jakarta).toLocalTime()

    private fun assertNear(expected: String, actual: LocalTime, toleranceMin: Long = 2) {
        val diff = abs(ChronoUnit.MINUTES.between(LocalTime.parse(expected), actual))
        assertTrue("expected ~$expected but was $actual", diff <= toleranceMin)
    }

    @Test
    fun jakartaKemenagMatchesPublishedSchedule() {
        // Kemenag RI schedule for Kota Jakarta (via api.myquran.com, city id 1301).
        val calc = PrayerTimeCalculator(-6.2088, 106.8456, CalcMethod.KEMENAG, AsrMadhab.SHAFII, 2)

        val jan = calc.timesFor(LocalDate.of(2025, 1, 1))
        assertNear("04:19", local(jan, Prayer.FAJR))
        assertNear("12:00", local(jan, Prayer.DHUHR))
        assertNear("15:26", local(jan, Prayer.ASR))
        assertNear("18:14", local(jan, Prayer.MAGHRIB))
        assertNear("19:29", local(jan, Prayer.ISHA))

        val nov = calc.timesFor(LocalDate.of(2026, 11, 7))
        assertNear("04:07", local(nov, Prayer.FAJR))
        assertNear("11:40", local(nov, Prayer.DHUHR))
        assertNear("14:58", local(nov, Prayer.ASR))
        assertNear("17:50", local(nov, Prayer.MAGHRIB))
        assertNear("19:02", local(nov, Prayer.ISHA))
    }

    @Test
    fun timesAreOrderedAndOnRequestedDate() {
        val calc = PrayerTimeCalculator(-6.2088, 106.8456, CalcMethod.KEMENAG, AsrMadhab.SHAFII)
        val date = LocalDate.of(2026, 11, 7)
        val t = calc.timesFor(date)
        assertEquals(5, t.size)
        val ordered = Prayer.entries.map { t.getValue(it) }
        assertEquals(ordered.sorted(), ordered)
        ordered.forEach { assertEquals(date, it.atZone(jakarta).toLocalDate()) }
    }

    @Test
    fun ummAlQuraIshaIs90MinutesAfterMaghrib() {
        val calc = PrayerTimeCalculator(21.4225, 39.8262, CalcMethod.UMM_AL_QURA, AsrMadhab.SHAFII)
        val t = calc.timesFor(LocalDate.of(2026, 3, 1))
        assertEquals(90, ChronoUnit.MINUTES.between(t.getValue(Prayer.MAGHRIB), t.getValue(Prayer.ISHA)))
    }
}
