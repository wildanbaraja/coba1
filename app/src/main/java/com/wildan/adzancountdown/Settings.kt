package com.wildan.adzancountdown

import android.content.Context
import androidx.core.content.edit

enum class TimeSource { CITY, COORDINATES }

/** Thin wrapper around SharedPreferences for the user's configuration. */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("adzan_settings", Context.MODE_PRIVATE)

    var source: TimeSource
        get() = runCatching { TimeSource.valueOf(prefs.getString(KEY_SOURCE, null)!!) }.getOrDefault(TimeSource.CITY)
        set(v) = prefs.edit { putString(KEY_SOURCE, v.name) }

    /** myQuran / Kemenag city id, e.g. "1301" for Kota Jakarta. */
    var cityId: String
        get() = prefs.getString(KEY_CITY_ID, null)?.takeIf(KemenagRepository::isValidCityId) ?: DEFAULT_CITY_ID
        set(v) {
            require(KemenagRepository.isValidCityId(v)) { "Invalid city id" }
            prefs.edit { putString(KEY_CITY_ID, v) }
        }

    var cityName: String
        get() = prefs.getString(KEY_CITY_NAME, null) ?: DEFAULT_CITY_NAME
        set(v) = prefs.edit { putString(KEY_CITY_NAME, v) }

    var latitude: Double
        get() = prefs.getString(KEY_LAT, null)?.toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: DEFAULT_LAT
        set(v) {
            require(v in -90.0..90.0) { "Invalid latitude" }
            prefs.edit { putString(KEY_LAT, v.toString()) }
        }

    var longitude: Double
        get() = prefs.getString(KEY_LNG, null)?.toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: DEFAULT_LNG
        set(v) {
            require(v in -180.0..180.0) { "Invalid longitude" }
            prefs.edit { putString(KEY_LNG, v.toString()) }
        }

    var method: CalcMethod
        get() = runCatching { CalcMethod.valueOf(prefs.getString(KEY_METHOD, null)!!) }.getOrDefault(CalcMethod.KEMENAG)
        set(v) = prefs.edit { putString(KEY_METHOD, v.name) }

    var madhab: AsrMadhab
        get() = runCatching { AsrMadhab.valueOf(prefs.getString(KEY_MADHAB, null)!!) }.getOrDefault(AsrMadhab.SHAFII)
        set(v) = prefs.edit { putString(KEY_MADHAB, v.name) }

    /** Ihtiyat / correction minutes added to every computed time. */
    var adjustMinutes: Int
        get() = prefs.getInt(KEY_ADJUST, 2).coerceIn(-MAX_ADJUST, MAX_ADJUST)
        set(v) = prefs.edit { putInt(KEY_ADJUST, v.coerceIn(-MAX_ADJUST, MAX_ADJUST)) }

    /** Length of the countdown after adzan, in minutes. */
    var countdownMinutes: Int
        get() = prefs.getInt(KEY_DURATION, 15).coerceIn(1, 60)
        set(v) = prefs.edit { putInt(KEY_DURATION, v.coerceIn(1, 60)) }

    fun isEnabled(prayer: Prayer): Boolean = prefs.getBoolean("enabled_${prayer.key}", true)
    fun setEnabled(prayer: Prayer, enabled: Boolean) = prefs.edit { putBoolean("enabled_${prayer.key}", enabled) }

    fun calculator() = PrayerTimeCalculator(latitude, longitude, method, madhab, adjustMinutes)

    companion object {
        private const val MAX_ADJUST = 30
        private const val KEY_SOURCE = "source"
        private const val KEY_CITY_ID = "city_id"
        private const val KEY_CITY_NAME = "city_name"
        private const val KEY_LAT = "lat"
        private const val KEY_LNG = "lng"
        private const val KEY_METHOD = "method"
        private const val KEY_MADHAB = "madhab"
        private const val KEY_ADJUST = "adjust"
        private const val KEY_DURATION = "duration"

        const val DEFAULT_CITY_ID = "1301"
        const val DEFAULT_CITY_NAME = "KOTA JAKARTA"

        // Jakarta, used until the user sets a location.
        const val DEFAULT_LAT = -6.2088
        const val DEFAULT_LNG = 106.8456
    }
}
