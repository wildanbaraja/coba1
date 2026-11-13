package com.islandbar.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Defaults are tuned for the Galaxy S25 Ultra's centred punch-hole camera. */
data class IslandConfig(
    val heightDp: Float = 36f,
    val idleWidthDp: Float = 112f,
    val compactWidthDp: Float = 236f,
    val offsetXDp: Float = 0f,
    val offsetYDp: Float = 0f,
    val showWhenIdle: Boolean = true,
    val showAlerts: Boolean = true,
    val showCharging: Boolean = true,
    val hideInLandscape: Boolean = true,
    val hidePausedMedia: Boolean = true,
)

object IslandSettings {
    private const val PREFS = "island_settings"
    private var prefs: SharedPreferences? = null

    private val _config = MutableStateFlow(IslandConfig())
    val config: StateFlow<IslandConfig> = _config.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val d = IslandConfig()
        _config.value = IslandConfig(
            heightDp = p.getFloat("heightDp", d.heightDp),
            idleWidthDp = p.getFloat("idleWidthDp", d.idleWidthDp),
            compactWidthDp = p.getFloat("compactWidthDp", d.compactWidthDp),
            offsetXDp = p.getFloat("offsetXDp", d.offsetXDp),
            offsetYDp = p.getFloat("offsetYDp", d.offsetYDp),
            showWhenIdle = p.getBoolean("showWhenIdle", d.showWhenIdle),
            showAlerts = p.getBoolean("showAlerts", d.showAlerts),
            showCharging = p.getBoolean("showCharging", d.showCharging),
            hideInLandscape = p.getBoolean("hideInLandscape", d.hideInLandscape),
            hidePausedMedia = p.getBoolean("hidePausedMedia", d.hidePausedMedia),
        )
    }

    fun update(transform: (IslandConfig) -> IslandConfig) {
        val c = transform(_config.value)
        _config.value = c
        prefs?.edit {
            putFloat("heightDp", c.heightDp)
            putFloat("idleWidthDp", c.idleWidthDp)
            putFloat("compactWidthDp", c.compactWidthDp)
            putFloat("offsetXDp", c.offsetXDp)
            putFloat("offsetYDp", c.offsetYDp)
            putBoolean("showWhenIdle", c.showWhenIdle)
            putBoolean("showAlerts", c.showAlerts)
            putBoolean("showCharging", c.showCharging)
            putBoolean("hideInLandscape", c.hideInLandscape)
            putBoolean("hidePausedMedia", c.hidePausedMedia)
        }
    }

    fun reset() = update { IslandConfig() }
}
