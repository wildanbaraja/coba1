package com.wildan.adzancountdown

import android.content.Context
import java.time.Instant
import java.time.LocalDate

/** Single place that decides where prayer times come from. */
object PrayerSchedule {
    data class DayTimes(val times: Map<Prayer, Instant>, val official: Boolean)

    /**
     * City mode: the downloaded Kemenag schedule for the chosen city. If that month isn't
     * downloaded yet (first launch offline, or month rollover without internet) we fall
     * back to the astronomical calculation so the countdown still happens.
     */
    fun timesFor(context: Context, date: LocalDate): DayTimes {
        val settings = Settings(context)
        if (settings.source == TimeSource.CITY) {
            KemenagRepository(context).timesFor(settings.cityId, date)?.let { return DayTimes(it, true) }
        }
        return DayTimes(settings.calculator().timesFor(date), false)
    }

    /** Downloads any missing months for the chosen city. Blocking; call off the main thread. */
    fun refresh(context: Context): Boolean {
        val settings = Settings(context)
        if (settings.source != TimeSource.CITY) return true
        return KemenagRepository(context).ensureCached(settings.cityId)
    }
}
