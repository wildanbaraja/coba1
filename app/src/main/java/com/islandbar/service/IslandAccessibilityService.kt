package com.islandbar.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Rect
import android.os.BatteryManager
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import com.islandbar.data.IslandRepository
import com.islandbar.data.IslandSettings
import com.islandbar.data.SystemUiState
import com.islandbar.model.TransientEvent
import com.islandbar.model.TransientKind
import com.islandbar.overlay.IslandWindow

/** Owns the overlay window for as long as the user keeps the service enabled. */
class IslandAccessibilityService : AccessibilityService() {

    private var window: IslandWindow? = null
    private val bounds = Rect()

    /**
     * Only trust "status bar missing ⇒ full screen" after we've actually seen
     * One UI's status bar window once, so an unexpected window layout can
     * never hide the island for good.
     */
    private var statusBarSeen = false

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_POWER_CONNECTED) return
            if (!IslandSettings.config.value.showCharging) return
            val bm = context.getSystemService(BatteryManager::class.java)
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
            IslandRepository.showTransient(
                TransientEvent(kind = TransientKind.CHARGING, batteryLevel = level, durationMs = 3000)
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        IslandSettings.init(this)
        window = IslandWindow(this, openNotificationShade = {
            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }).also { it.attach() }
        ContextCompat.registerReceiver(
            this, powerReceiver, IntentFilter(Intent.ACTION_POWER_CONNECTED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        IslandRepository.overlayRunning.value = true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        window?.onConfigurationChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            updateSystemUi()
        }
    }

    /** Works out from window types/bounds alone whether One UI's shade is down or an app is full screen. */
    private fun updateSystemUi() {
        val list = runCatching { windows }.getOrNull() ?: return
        val screen = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val sw = screen.width()
        val sh = screen.height()
        var shade = false
        var statusBar = false
        for (w in list) {
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            w.getBoundsInScreen(bounds)
            if (bounds.height() > sh * 0.5f && bounds.width() > sw * 0.8f) shade = true
            if (bounds.top <= 0 && bounds.height() in 1..(sh / 12) && bounds.width() > sw * 0.8f) statusBar = true
        }
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if (statusBar) statusBarSeen = true
        val state = SystemUiState(
            shadeOpen = shade && !locked,
            fullscreen = statusBarSeen && !statusBar && !shade && !locked,
        )
        if (IslandRepository.systemUi.value != state) IslandRepository.systemUi.value = state
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        val w = window ?: return
        window = null
        runCatching { unregisterReceiver(powerReceiver) }
        w.detach()
        IslandRepository.overlayRunning.value = false
        IslandRepository.systemUi.value = SystemUiState()
    }
}
