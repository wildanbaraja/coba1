package com.islandbar.ui.settings

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.islandbar.data.IslandConfig
import com.islandbar.data.IslandRepository
import com.islandbar.data.IslandSettings
import com.islandbar.model.ActivityType
import com.islandbar.model.IslandAction
import com.islandbar.model.ActionKind
import com.islandbar.model.LiveActivity
import com.islandbar.model.MediaInfo
import com.islandbar.model.TransientEvent
import com.islandbar.model.TransientKind
import com.islandbar.service.IslandAccessibilityService
import com.islandbar.service.IslandNotificationListener
import com.islandbar.service.IslandNotificationListener.Companion.DEMO_PREFIX
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IslandSettings.init(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF0A84FF), background = Color.Black, surface = Color.Black)) {
                SettingsScreen()
            }
        }
    }

    @Composable
    private fun SettingsScreen() {
        val config by IslandSettings.config.collectAsStateWithLifecycle()
        val overlayOn by IslandRepository.overlayRunning.collectAsStateWithLifecycle()
        val listenerOn by IslandRepository.listenerConnected.collectAsStateWithLifecycle()

        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(36.dp)) // keep the header clear of the island itself
            Text("Island Bar", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                "An animated island around your front camera. Long-press it to expand, tap to open the app.",
                color = Color(0xFF8E8E93), fontSize = 14.sp,
            )

            Section("Setup") {
                PermissionRow(
                    "Island overlay",
                    "Accessibility service that draws the island over the status bar.",
                    overlayOn,
                ) { openAccessibilitySettings() }
                PermissionRow(
                    "Notification access",
                    "Lets music, calls, timers and downloads appear in the island.",
                    listenerOn,
                ) { openNotificationAccess() }
                if (!overlayOn || !listenerOn) {
                    Text(
                        "Samsung blocks these for sideloaded apps. If a switch is greyed out, open App info › ⋮ › " +
                            "\"Allow restricted settings\", then try again.",
                        color = Color(0xFFFF9F0A), fontSize = 13.sp,
                    )
                    TextButton(onClick = ::openAppInfo) { Text("Open App info") }
                }
            }

            Section("Size & position") {
                SliderRow("Height", config.heightDp, 26f..52f) { v -> IslandSettings.update { it.copy(heightDp = v) } }
                SliderRow("Idle width", config.idleWidthDp, 50f..220f) { v -> IslandSettings.update { it.copy(idleWidthDp = v) } }
                SliderRow("Active width", config.compactWidthDp, 150f..340f) { v -> IslandSettings.update { it.copy(compactWidthDp = v) } }
                SliderRow("Horizontal offset", config.offsetXDp, -40f..40f) { v -> IslandSettings.update { it.copy(offsetXDp = v) } }
                SliderRow("Vertical offset", config.offsetYDp, -20f..30f) { v -> IslandSettings.update { it.copy(offsetYDp = v) } }
                TextButton(onClick = { IslandSettings.update { it.copy(heightDp = IslandConfig().heightDp, idleWidthDp = IslandConfig().idleWidthDp, compactWidthDp = IslandConfig().compactWidthDp, offsetXDp = 0f, offsetYDp = 0f) } }) {
                    Text("Reset to S25 Ultra defaults")
                }
            }

            Section("Behaviour") {
                SwitchRow("Show pill when idle", config.showWhenIdle) { v -> IslandSettings.update { it.copy(showWhenIdle = v) } }
                SwitchRow("Pop up new notifications", config.showAlerts) { v -> IslandSettings.update { it.copy(showAlerts = v) } }
                SwitchRow("Charging animation", config.showCharging) { v -> IslandSettings.update { it.copy(showCharging = v) } }
                SwitchRow("Hide paused music", config.hidePausedMedia) { v -> IslandSettings.update { it.copy(hidePausedMedia = v) } }
                SwitchRow("Hide in landscape", config.hideInLandscape) { v -> IslandSettings.update { it.copy(hideInLandscape = v) } }
            }

            Section("Try it") {
                Text("Add sample activities to preview the island without waiting for real ones.", color = Color(0xFF8E8E93), fontSize = 13.sp)
                Demos()
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Demos() {
        val now = System.currentTimeMillis()
        val icon = applicationInfo.loadIcon(packageManager)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                IslandRepository.upsert(
                    LiveActivity(
                        "${DEMO_PREFIX}music", ActivityType.MEDIA, packageName, "Midnight Drive", "Neon Coast",
                        media = MediaInfo("Midnight Drive", "Neon Coast", null, true, 214_000, 61_000),
                    )
                )
            }) { Text("Music") }
            FilledTonalButton(onClick = {
                IslandRepository.upsert(
                    LiveActivity(
                        "${DEMO_PREFIX}timer", ActivityType.TIMER, packageName, "Timer", "Pasta",
                        chronometerBase = now + 5 * 60_000, countDown = true,
                        actions = listOf(IslandAction("Pause", null), IslandAction("Cancel", null)),
                    )
                )
            }) { Text("Timer") }
            FilledTonalButton(onClick = {
                IslandRepository.upsert(
                    LiveActivity(
                        "${DEMO_PREFIX}call", ActivityType.CALL, packageName, "Alex", "Mobile",
                        appIcon = icon, chronometerBase = now,
                        actions = listOf(IslandAction("End", null, ActionKind.DECLINE)),
                    )
                )
            }) { Text("Call") }
            FilledTonalButton(onClick = {
                IslandRepository.upsert(
                    LiveActivity(
                        "${DEMO_PREFIX}download", ActivityType.PROGRESS, packageName, "Downloading", "holiday-photos.zip",
                        appIcon = icon, progress = 0.62f,
                    )
                )
            }) { Text("Download") }
            FilledTonalButton(onClick = {
                IslandRepository.showTransient(
                    TransientEvent(TransientKind.ALERT, title = "Sam", text = "Are we still on for dinner tonight? 🍜", appIcon = icon)
                )
            }) { Text("Alert") }
            FilledTonalButton(onClick = {
                IslandRepository.showTransient(TransientEvent(TransientKind.CHARGING, batteryLevel = 78, durationMs = 3000))
            }) { Text("Charging") }
            OutlinedButton(onClick = { IslandRepository.removeIf { it.key.startsWith(DEMO_PREFIX) } }) { Text("Clear") }
        }
    }

    // --- Settings intents -------------------------------------------------

    private fun openAccessibilitySettings() {
        val component = ComponentName(this, IslandAccessibilityService::class.java).flattenToString()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", component)
            putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", component) })
        }
        startActivity(intent)
    }

    private fun openNotificationAccess() {
        val component = ComponentName(this, IslandNotificationListener::class.java)
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        runCatching { startActivity(detail) }
            .onFailure { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Color.White)
            content()
        }
    }
}

@Composable
private fun PermissionRow(title: String, subtitle: String, granted: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null,
            tint = if (granted) Color(0xFF30D158) else Color(0xFFFF9F0A),
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp)
            Text(subtitle, color = Color(0xFF8E8E93), fontSize = 12.sp)
        }
        if (!granted) {
            Spacer(Modifier.width(8.dp))
            Button(onClick = onClick) { Text("Enable") }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("${value.roundToInt()} dp", color = Color(0xFF8E8E93), fontSize = 14.sp)
        }
        Slider(value = value, onValueChange = { onChange(it.roundToInt().toFloat()) }, valueRange = range)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
