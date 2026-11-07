package com.wildan.adzancountdown

import android.content.Context
import androidx.core.content.edit

/** Thin wrapper around SharedPreferences for the user's configuration. */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("adzan_settings", Context.MODE_PRIVATE)

    val hasLocation: Boolean get() = prefs.contains(KEY_LAT) && prefs.contains(KEY_LNG)

    var latitude: Double
        get() = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: DEFAULT_LAT
        set(v) = prefs.edit { putString(KEY_LAT, v.toString()) }

    var longitude: Double
        get() = prefs.getString(KEY_LNG, null)?.toDoubleOrNull() ?: DEFAULT_LNG
        set(v) = prefs.edit { putString(KEY_LNG, v.toString()) }

    var method: CalcMethod
        get() = runCatching { CalcMethod.valueOf(prefs.getString(KEY_METHOD, null)!!) }.getOrDefault(CalcMethod.KEMENAG)
        set(v) = prefs.edit { putString(KEY_METHOD, v.name) }

    var madhab: AsrMadhab
        get() = runCatching { AsrMadhab.valueOf(prefs.getString(KEY_MADHAB, null)!!) }.getOrDefault(AsrMadhab.SHAFII)
        set(v) = prefs.edit { putString(KEY_MADHAB, v.name) }

    /** Ihtiyat / correction minutes added to every computed time. */
    var adjustMinutes: Int
        get() = prefs.getInt(KEY_ADJUST, 2)
        set(v) = prefs.edit { putInt(KEY_ADJUST, v) }

    /** Length of the countdown after adzan, in minutes. */
    var countdownMinutes: Int
        get() = prefs.getInt(KEY_DURATION, 15)
        set(v) = prefs.edit { putInt(KEY_DURATION, v.coerceIn(1, 60)) }

    fun isEnabled(prayer: Prayer): Boolean = prefs.getBoolean("enabled_${prayer.key}", true)
    fun setEnabled(prayer: Prayer, enabled: Boolean) = prefs.edit { putBoolean("enabled_${prayer.key}", enabled) }

    fun calculator() = PrayerTimeCalculator(latitude, longitude, method, madhab, adjustMinutes)

    companion object {
        private const val KEY_LAT = "lat"
        private const val KEY_LNG = "lng"
        private const val KEY_METHOD = "method"
        private const val KEY_MADHAB = "madhab"
        private const val KEY_ADJUST = "adjust"
        private const val KEY_DURATION = "duration"

        // Jakarta, used until the user sets a location.
        const val DEFAULT_LAT = -6.2088
        const val DEFAULT_LNG = 106.8456
    }
}
