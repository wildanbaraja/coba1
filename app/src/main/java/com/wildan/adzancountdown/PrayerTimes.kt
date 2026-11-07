package com.wildan.adzancountdown

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

enum class Prayer(val key: String, val displayName: String) {
    FAJR("fajr", "Subuh"),
    DHUHR("dhuhr", "Dzuhur"),
    ASR("asr", "Ashar"),
    MAGHRIB("maghrib", "Maghrib"),
    ISHA("isha", "Isya"),
}

/**
 * Calculation method. [ishaMinutesAfterMaghrib] is used instead of [ishaAngle] when not null.
 */
enum class CalcMethod(
    val label: String,
    val fajrAngle: Double,
    val ishaAngle: Double,
    val ishaMinutesAfterMaghrib: Int? = null,
) {
    KEMENAG("Kemenag RI (Indonesia)", 20.0, 18.0),
    JAKIM("JAKIM (Malaysia)", 20.0, 18.0),
    MUIS("MUIS (Singapore)", 20.0, 18.0),
    MWL("Muslim World League", 18.0, 17.0),
    ISNA("ISNA (North America)", 15.0, 15.0),
    EGYPT("Egyptian General Authority", 19.5, 17.5),
    KARACHI("Univ. of Islamic Sciences, Karachi", 18.0, 18.0),
    UMM_AL_QURA("Umm al-Qura (Makkah)", 18.5, 0.0, ishaMinutesAfterMaghrib = 90),
}

enum class AsrMadhab(val label: String, val shadowFactor: Double) {
    SHAFII("Shafi'i / Maliki / Hanbali", 1.0),
    HANAFI("Hanafi", 2.0),
}

/**
 * Astronomical prayer-time calculation (same algorithm family as praytimes.org).
 *
 * All results are absolute [Instant]s, so no time-zone handling is needed: the
 * device's zone is only used later when formatting for display.
 */
class PrayerTimeCalculator(
    private val latitude: Double,
    private val longitude: Double,
    private val method: CalcMethod,
    private val madhab: AsrMadhab,
    /** Extra minutes added to every time ("ihtiyat"); Kemenag customarily uses +2. */
    private val adjustMinutes: Int = 0,
) {

    /** Returns the prayer times for [date] (the local calendar date at the location). */
    fun timesFor(date: LocalDate): Map<Prayer, Instant> {
        val jDate = julian(date.year, date.monthValue, date.dayOfMonth) - longitude / (15.0 * 24.0)

        // Two passes: first with rough guesses, then refined with the first result.
        var t = doubleArrayOf(5.0, 6.0, 12.0, 13.0, 18.0, 18.0)
        repeat(2) { t = computePass(jDate, t) }
        val sunset = t[4]
        val isha = method.ishaMinutesAfterMaghrib?.let { sunset + it / 60.0 } ?: t[5]
        val raw = mapOf(
            Prayer.FAJR to t[0],
            Prayer.DHUHR to t[2],
            Prayer.ASR to t[3],
            Prayer.MAGHRIB to sunset,
            Prayer.ISHA to isha,
        )

        val midnightUtc = date.atStartOfDay().toInstant(ZoneOffset.UTC)
        return raw.filterValues { !it.isNaN() }.mapValues { (_, localSolarHours) ->
            // Convert from local solar time to UTC hours.
            val utcHours = localSolarHours - longitude / 15.0
            val seconds = (utcHours * 3600.0).roundToLong() + adjustMinutes * 60L
            // Round to the whole minute, like published schedules.
            val rounded = Math.floorDiv(seconds + 30, 60L) * 60L
            midnightUtc.plusSeconds(rounded)
        }
    }

    private fun computePass(jDate: Double, guess: DoubleArray): DoubleArray {
        val dayPortion = guess.map { it / 24.0 }
        val fajr = sunAngleTime(jDate, method.fajrAngle, dayPortion[0], ccw = true)
        val sunrise = sunAngleTime(jDate, RISE_SET_ANGLE, dayPortion[1], ccw = true)
        val dhuhr = midDay(jDate, dayPortion[2])
        val asr = asrTime(jDate, madhab.shadowFactor, dayPortion[3])
        val sunset = sunAngleTime(jDate, RISE_SET_ANGLE, dayPortion[4], ccw = false)
        val isha = sunAngleTime(jDate, method.ishaAngle, dayPortion[5], ccw = false)
        return doubleArrayOf(fajr, sunrise, dhuhr, asr, sunset, isha)
    }

    private fun midDay(jDate: Double, t: Double): Double {
        val eqt = sunPosition(jDate + t).equation
        return fixHour(12.0 - eqt)
    }

    private fun sunAngleTime(jDate: Double, angle: Double, t: Double, ccw: Boolean): Double {
        val decl = sunPosition(jDate + t).declination
        val noon = midDay(jDate, t)
        val cosH = (-dSin(angle) - dSin(decl) * dSin(latitude)) / (dCos(decl) * dCos(latitude))
        if (cosH < -1.0 || cosH > 1.0) return Double.NaN // sun never reaches that angle
        val tt = dAcos(cosH) / 15.0
        return noon + if (ccw) -tt else tt
    }

    private fun asrTime(jDate: Double, factor: Double, t: Double): Double {
        val decl = sunPosition(jDate + t).declination
        val angle = -dAcot(factor + dTan(abs(latitude - decl)))
        return sunAngleTime(jDate, angle, t, ccw = false)
    }

    private data class SunPosition(val declination: Double, val equation: Double)

    private fun sunPosition(jd: Double): SunPosition {
        val d = jd - 2451545.0
        val g = fixAngle(357.529 + 0.98560028 * d)
        val q = fixAngle(280.459 + 0.98564736 * d)
        val l = fixAngle(q + 1.915 * dSin(g) + 0.020 * dSin(2 * g))
        val e = 23.439 - 0.00000036 * d
        val ra = dAtan2(dCos(e) * dSin(l), dCos(l)) / 15.0
        val eqt = q / 15.0 - fixHour(ra)
        val decl = dAsin(dSin(e) * dSin(l))
        return SunPosition(decl, eqt)
    }

    companion object {
        private const val RISE_SET_ANGLE = 0.833

        fun julian(year: Int, month: Int, day: Int): Double {
            var y = year
            var m = month
            if (m <= 2) { y -= 1; m += 12 }
            val a = floor(y / 100.0)
            val b = 2 - a + floor(a / 4.0)
            return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
        }

        private fun dSin(d: Double) = sin(Math.toRadians(d))
        private fun dCos(d: Double) = cos(Math.toRadians(d))
        private fun dTan(d: Double) = tan(Math.toRadians(d))
        private fun dAsin(x: Double) = Math.toDegrees(asin(x))
        private fun dAcos(x: Double) = Math.toDegrees(acos(x))
        private fun dAtan2(y: Double, x: Double) = Math.toDegrees(atan2(y, x))
        private fun dAcot(x: Double) = Math.toDegrees(atan(1.0 / x))
        private fun fixAngle(a: Double) = fix(a, 360.0)
        private fun fixHour(a: Double) = fix(a, 24.0)
        private fun fix(a: Double, b: Double): Double {
            val r = a - b * floor(a / b)
            return if (r < 0) r + b else r
        }
    }
}
