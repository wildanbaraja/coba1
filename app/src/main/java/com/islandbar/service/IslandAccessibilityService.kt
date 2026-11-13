package com.islandbar.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.BatteryManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.islandbar.data.IslandRepository
import com.islandbar.data.IslandSettings
import com.islandbar.model.TransientEvent
import com.islandbar.model.TransientKind
import com.islandbar.overlay.IslandWindow

/** Owns the overlay window for as long as the user keeps the service enabled. */
class IslandAccessibilityService : AccessibilityService() {

    private var window: IslandWindow? = null

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
        window = IslandWindow(this).also { it.attach() }
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

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
    }
}
