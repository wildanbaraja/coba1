package com.islandbar.ui.settings

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.islandbar.data.IslandConfig
import com.islandbar.data.IslandRepository
import com.islandbar.data.IslandSettings
import com.islandbar.model.ActionKind
import com.islandbar.model.ActivityType
import com.islandbar.model.IslandAction
import com.islandbar.model.LiveActivity
import com.islandbar.model.MediaInfo
import com.islandbar.model.TransientEvent
import com.islandbar.model.TransientKind
import com.islandbar.overlay.IslandGeometry
import com.islandbar.service.IslandAccessibilityService
import com.islandbar.service.IslandNotificationListener
import com.islandbar.service.IslandNotificationListener.Companion.DEMO_PREFIX
import com.islandbar.ui.island.ContinuousRoundedShape
import com.islandbar.ui.island.Inter
import com.islandbar.ui.island.Island
import com.islandbar.ui.island.IslandCallbacks
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.roundToInt

private object Palette {
    val Background = Color(0xFF000000)
    val Group = Color(0xFF1C1C1E)
    val Separator = Color(0xFF38383A)
    val Secondary = Color(0xFF98989F)
    val Blue = Color(0xFF0A84FF)
    val Green = Color(0xFF32D74B)
    val Orange = Color(0xFFFF9F0A)
    val Red = Color(0xFFFF453A)
    val Purple = Color(0xFFBF5AF2)
    val Indigo = Color(0xFF5E5CE6)
    val Pink = Color(0xFFFF375F)
}

private val Body = TextStyle(fontFamily = Inter, fontSize = 16.sp, letterSpacing = (-0.3).sp, color = Color.White)
private val Footnote = TextStyle(fontFamily = Inter, fontSize = 13.sp, letterSpacing = (-0.1).sp, color = Palette.Secondary)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IslandSettings.init(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Palette.Blue, background = Palette.Background, surface = Palette.Background)) {
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
                .background(Palette.Background)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(44.dp)) // keep the header clear of the island itself
            Text("Island Bar", style = Body.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp))
            Spacer(Modifier.height(4.dp))
            Text("Long-press the island to expand · tap to open the app", style = Footnote.copy(fontSize = 15.sp))
            Spacer(Modifier.height(20.dp))

            LivePreview(config)

            Header("Setup")
            Group {
                PermissionRow(Icons.Rounded.Layers, Palette.Indigo, "Island overlay", "Draws the island over the status bar", overlayOn) { openAccessibilitySettings() }
                Divider()
                PermissionRow(Icons.Rounded.Notifications, Palette.Red, "Notification access", "Music, calls, timers & alerts", listenerOn) { openNotificationAccess() }
            }
            if (!overlayOn || !listenerOn) {
                Footer(
                    "Greyed-out switch? Samsung blocks these for sideloaded apps. Open App info › ⋮ › Allow restricted settings, then try again.",
                    action = "Open App info", onAction = ::openAppInfo,
                )
            }

            Header("Size & position")
            Group {
                SliderRow("Height", config.heightDp, 26f..52f) { v -> IslandSettings.update { it.copy(heightDp = v) } }
                Divider()
                SliderRow("Idle width", config.idleWidthDp, 50f..220f) { v -> IslandSettings.update { it.copy(idleWidthDp = v) } }
                Divider()
                SliderRow("Active width", config.compactWidthDp, 150f..340f) { v -> IslandSettings.update { it.copy(compactWidthDp = v) } }
                Divider()
                SliderRow("Horizontal offset", config.offsetXDp, -40f..40f) { v -> IslandSettings.update { it.copy(offsetXDp = v) } }
                Divider()
                SliderRow("Vertical offset", config.offsetYDp, -20f..30f) { v -> IslandSettings.update { it.copy(offsetYDp = v) } }
                Divider()
                LinkRow("Reset to S25 Ultra defaults") {
                    val d = IslandConfig()
                    IslandSettings.update { it.copy(heightDp = d.heightDp, idleWidthDp = d.idleWidthDp, compactWidthDp = d.compactWidthDp, offsetXDp = 0f, offsetYDp = 0f) }
                }
            }

            Header("Behaviour")
            Group {
                SwitchRow("Show pill when idle", config.showWhenIdle) { v -> IslandSettings.update { it.copy(showWhenIdle = v) } }
                Divider()
                SwitchRow("Pop up new notifications", config.showAlerts) { v -> IslandSettings.update { it.copy(showAlerts = v) } }
                Divider()
                SwitchRow("Charging animation", config.showCharging) { v -> IslandSettings.update { it.copy(showCharging = v) } }
                Divider()
                SwitchRow("Hide paused music", config.hidePausedMedia) { v -> IslandSettings.update { it.copy(hidePausedMedia = v) } }
                Divider()
                SwitchRow("Hide in landscape", config.hideInLandscape) { v -> IslandSettings.update { it.copy(hideInLandscape = v) } }
            }

            Header("Try it on the real island")
            Group { Demos() }
            Footer("Adds sample activities to the island at the top of your screen. Long-press them to expand.")
            Spacer(Modifier.height(40.dp))
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Demos() {
        val icon = remember { applicationInfo.loadIcon(packageManager) }
        FlowRow(
            Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DemoChip(Icons.Rounded.MusicNote, Palette.Pink, "Music") {
                IslandRepository.upsert(demoMusic("${DEMO_PREFIX}music"))
            }
            DemoChip(Icons.Rounded.Timer, Palette.Orange, "Timer") {
                IslandRepository.upsert(demoTimer("${DEMO_PREFIX}timer"))
            }
            DemoChip(Icons.Rounded.Call, Palette.Green, "Call") {
                IslandRepository.upsert(demoCall("${DEMO_PREFIX}call", icon))
            }
            DemoChip(Icons.Rounded.Download, Palette.Blue, "Download") {
                IslandRepository.upsert(
                    LiveActivity("${DEMO_PREFIX}download", ActivityType.PROGRESS, packageName, "Downloading", "holiday-photos.zip", appIcon = icon, progress = 0.62f)
                )
            }
            DemoChip(Icons.Rounded.Sms, Palette.Green, "Alert") {
                IslandRepository.showTransient(demoAlert(icon))
            }
            DemoChip(Icons.Rounded.BatteryChargingFull, Palette.Green, "Charging") {
                IslandRepository.showTransient(TransientEvent(TransientKind.CHARGING, batteryLevel = 78, durationMs = 3000))
            }
            DemoChip(null, Palette.Red, "Clear all") { IslandRepository.removeIf { it.key.startsWith(DEMO_PREFIX) } }
        }
    }

    private fun demoMusic(key: String) = LiveActivity(
        key, ActivityType.MEDIA, packageName, "Midnight Drive", "Neon Coast",
        media = MediaInfo("Midnight Drive", "Neon Coast", null, true, 214_000, 61_000),
    )

    private fun demoTimer(key: String) = LiveActivity(
        key, ActivityType.TIMER, packageName, "Timer", "Pasta",
        chronometerBase = System.currentTimeMillis() + 5 * 60_000, countDown = true,
        actions = listOf(IslandAction("Pause", null), IslandAction("Cancel", null)),
    )

    private fun demoCall(key: String, icon: android.graphics.drawable.Drawable?) = LiveActivity(
        key, ActivityType.CALL, packageName, "Alex Morgan", "mobile",
        appIcon = icon, chronometerBase = System.currentTimeMillis(),
        actions = listOf(IslandAction("Decline", null, ActionKind.DECLINE), IslandAction("Answer", null, ActionKind.ACCEPT)),
    )

    private fun demoAlert(icon: android.graphics.drawable.Drawable?) =
        TransientEvent(TransientKind.ALERT, title = "Sam", text = "Are we still on for dinner tonight? 🍜", appIcon = icon)

    /** A miniature phone that plays through the island's states on a loop. */
    @Composable
    private fun LivePreview(config: IslandConfig) {
        val icon = remember { applicationInfo.loadIcon(packageManager) }
        val music = remember { demoMusic("preview:music") }
        val timer = remember { demoTimer("preview:timer") }
        val call = remember { demoCall("preview:call", icon) }
        var step by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            val durations = longArrayOf(1600, 2200, 3200, 2600, 2600, 3200, 2400, 3200)
            while (true) {
                delay(durations[step % durations.size])
                step = (step + 1) % durations.size
            }
        }
        val alert = remember(step) { demoAlert(icon) }
        val charging = remember(step) { TransientEvent(TransientKind.CHARGING, batteryLevel = 78, durationMs = 60_000) }
        val (activities, transient, expanded) = when (step) {
            0 -> Triple(emptyList(), null, null)
            1 -> Triple(listOf(music), null, null)
            2 -> Triple(listOf(music), null, music.key)
            3 -> Triple(listOf(timer, music), null, null)
            4 -> Triple(emptyList<LiveActivity>(), charging, null)
            5 -> Triple(emptyList(), alert, null)
            6 -> Triple(listOf(timer), null, null)
            else -> Triple(listOf(call), null, call.key)
        }

        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(270.dp)
                .clip(ContinuousRoundedShape(28.dp))
                .background(
                    Brush.linearGradient(listOf(Color(0xFF1B2A6B), Color(0xFF6A3FA0), Color(0xFFE06C8A)))
                ),
        ) {
            val density = LocalDensity.current
            val wPx = with(density) { maxWidth.roundToPx() }
            val cut = with(density) { 11.dp.roundToPx() }
            val top = with(density) { 22.dp.roundToPx() }
            val geo = remember(wPx) { IslandGeometry(wPx, wPx * 2, wPx / 2, top, cut, hasCutout = true, isLandscape = false) }
            val noop = remember { IslandCallbacks({ _, _, _ -> }, { _, _ -> }) }
            val previewConfig = config.copy(showWhenIdle = true, hideInLandscape = false)
            // Status bar time, so it reads as a phone.
            Text("9:41", style = Body.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = 26.dp, top = 12.dp))
            Box(Modifier.fillMaxSize().padding(top = with(density) { (top - config.heightDp.dp.roundToPx() / 2).toDp() })) {
                Island(geo, previewConfig, activities, transient, emptyFlow(), noop, requestedExpandedKey = expanded)
            }
            // A little "camera" so the preview shows where the hole sits.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 22.dp - 5.5.dp)
                    .size(11.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color(0xFF0D0D10))
            )
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

// --- iOS-style grouped list building blocks ----------------------------------

@Composable
private fun Header(text: String) {
    Text(
        text.uppercase(), style = Footnote.copy(letterSpacing = 0.2.sp),
        modifier = Modifier.padding(start = 16.dp, top = 28.dp, bottom = 7.dp),
    )
}

@Composable
private fun Footer(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp)) {
        Text(text, style = Footnote)
        if (action != null && onAction != null) {
            Text(action, style = Footnote.copy(color = Palette.Blue), modifier = Modifier.padding(top = 6.dp).clickable(onClick = onAction))
        }
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(ContinuousRoundedShape(14.dp)).background(Palette.Group)) { content() }
}

@Composable
private fun Divider(inset: Int = 16) {
    Box(Modifier.padding(start = inset.dp).fillMaxWidth().height(0.5.dp).background(Palette.Separator))
}

@Composable
private fun IconTile(icon: ImageVector, color: Color) {
    Box(Modifier.size(30.dp).clip(ContinuousRoundedShape(7.dp)).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, color: Color, title: String, subtitle: String, granted: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !granted, onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, color)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Body)
            Text(subtitle, style = Footnote)
        }
        if (granted) {
            Icon(Icons.Rounded.Check, null, tint = Palette.Green, modifier = Modifier.size(22.dp))
        } else {
            Text("Enable", style = Body.copy(color = Palette.Blue))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Palette.Secondary, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp)) {
        Row {
            Text(label, style = Body, modifier = Modifier.weight(1f))
            Text("${value.roundToInt()} dp", style = Body.copy(color = Palette.Secondary, fontFeatureSettings = "tnum"))
        }
        Slider(
            value = value, onValueChange = { onChange(it.roundToInt().toFloat()) }, valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color.White, activeTrackColor = Palette.Blue, inactiveTrackColor = Color(0xFF3A3A3C),
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Body, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Palette.Green, checkedBorderColor = Palette.Green,
                uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF39393D), uncheckedBorderColor = Color(0xFF39393D),
            ),
        )
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Text(label, style = Body.copy(color = Palette.Blue), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp))
}

@Composable
private fun DemoChip(icon: ImageVector?, color: Color, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(ContinuousRoundedShape(18.dp))
            .background(Color(0xFF2C2C2E))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = Body.copy(fontSize = 15.sp, color = if (icon == null) color else Color.White))
    }
}
