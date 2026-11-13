package com.islandbar.ui.settings

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.islandbar.data.IslandConfig
import com.islandbar.data.IslandFont
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
import com.islandbar.ui.island.Island
import com.islandbar.ui.island.IslandCallbacks
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.roundToInt

/** One UI 7 colours, light and dark. */
private data class OneUiColors(
    val background: Color,
    val card: Color,
    val text: Color,
    val secondary: Color,
    val divider: Color,
    val accent: Color,
    val chip: Color,
    val trackOff: Color,
)

private val OneUiLight = OneUiColors(
    background = Color(0xFFF2F2F2), card = Color(0xFFFCFCFC), text = Color(0xFF010101),
    secondary = Color(0xFF7A7A7A), divider = Color(0xFFE3E3E3), accent = Color(0xFF387AFF),
    chip = Color(0xFFEDEDED), trackOff = Color(0xFFD4D4D4),
)
private val OneUiDark = OneUiColors(
    background = Color(0xFF010101), card = Color(0xFF171717), text = Color(0xFFFAFAFA),
    secondary = Color(0xFF9A9A9A), divider = Color(0xFF2B2B2B), accent = Color(0xFF3E91FF),
    chip = Color(0xFF262626), trackOff = Color(0xFF4A4A4A),
)

private val LocalOneUi = staticCompositionLocalOf { OneUiDark }

private object Tint {
    val Green = Color(0xFF34C759)
    val Orange = Color(0xFFFF9500)
    val Red = Color(0xFFFF3B30)
    val Blue = Color(0xFF387AFF)
    val Indigo = Color(0xFF5E5CE6)
    val Pink = Color(0xFFFF2D55)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IslandSettings.init(this)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val c = if (dark) OneUiDark else OneUiLight
            val scheme = if (dark) darkColorScheme(primary = c.accent, background = c.background, surface = c.card)
            else lightColorScheme(primary = c.accent, background = c.background, surface = c.card)
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(LocalOneUi provides c) { SettingsScreen() }
            }
        }
    }

    @Composable
    private fun SettingsScreen() {
        val c = LocalOneUi.current
        val config by IslandSettings.config.collectAsStateWithLifecycle()
        val overlayOn by IslandRepository.overlayRunning.collectAsStateWithLifecycle()
        val listenerOn by IslandRepository.listenerConnected.collectAsStateWithLifecycle()
        val batteryOk by rememberBatteryUnrestricted()
        val ready = overlayOn && listenerOn

        val scroll = rememberScrollState()
        val density = LocalDensity.current
        // One UI's "reachability" header: the top ~third of the screen is title only,
        // so everything you tap sits within thumb reach. It collapses as you scroll.
        val headerHeight = (LocalConfiguration.current.screenHeightDp * 0.34f).dp.coerceIn(220.dp, 340.dp)
        val headerPx = with(density) { headerHeight.toPx() }

        Box(Modifier.fillMaxSize().background(c.background)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(horizontal = 12.dp),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(headerHeight)
                        .graphicsLayer { alpha = 1f - (scroll.value / (headerPx * 0.55f)).coerceIn(0f, 1f) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Spacer(Modifier.height(24.dp))
                    Text("Island Bar", style = TextStyle(fontSize = 38.sp, fontWeight = FontWeight.Normal, color = c.text, letterSpacing = (-0.5).sp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (ready) "Running" else "Finish setup to start",
                        style = TextStyle(fontSize = 15.sp, color = if (ready) c.accent else Tint.Orange),
                    )
                }

                LivePreview(config)

                Category("Setup")
                Card {
                    PermissionRow(Icons.Rounded.Layers, Tint.Indigo, "Island overlay", "Draws the island on screen", overlayOn) { openAccessibilitySettings() }
                    Divider()
                    PermissionRow(Icons.Rounded.Notifications, Tint.Red, "Notification access", "Music, calls, timers & alerts", listenerOn) { openNotificationAccess() }
                    Divider()
                    PermissionRow(Icons.Rounded.BatterySaver, Tint.Green, "Battery: Unrestricted", "Keeps the island running", batteryOk) { requestUnrestrictedBattery() }
                }
                if (!overlayOn || !listenerOn) {
                    Note(
                        "Switch greyed out? One UI blocks these for apps installed outside Galaxy Store / Play. " +
                            "Open App info › ⋮ › Allow restricted settings, then try again.",
                        action = "Open App info", onAction = ::openAppInfo,
                    )
                }

                Category("Look")
                Card {
                    FontPicker(config.font)
                    Divider()
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
                    LinkRow("Reset to Galaxy S25 Ultra defaults") {
                        val d = IslandConfig()
                        IslandSettings.update {
                            it.copy(heightDp = d.heightDp, idleWidthDp = d.idleWidthDp, compactWidthDp = d.compactWidthDp, offsetXDp = 0f, offsetYDp = 0f)
                        }
                    }
                }

                Category("Behaviour")
                Card {
                    SwitchRow("Show pill when idle", null, config.showWhenIdle) { v -> IslandSettings.update { it.copy(showWhenIdle = v) } }
                    Divider()
                    SwitchRow("Pop up new notifications", null, config.showAlerts) { v -> IslandSettings.update { it.copy(showAlerts = v) } }
                    Divider()
                    SwitchRow("Charging animation", null, config.showCharging) { v -> IslandSettings.update { it.copy(showCharging = v) } }
                    Divider()
                    SwitchRow("Hide paused music", "After 45 seconds, like iPhone", config.hidePausedMedia) { v -> IslandSettings.update { it.copy(hidePausedMedia = v) } }
                    Divider()
                    SwitchRow("S Pen hover to peek", "Hover the S Pen over the island", config.sPenHover) { v -> IslandSettings.update { it.copy(sPenHover = v) } }
                    Divider()
                    SwitchRow("Hide in full-screen apps", "Videos and games", config.hideInFullscreen) { v -> IslandSettings.update { it.copy(hideInFullscreen = v) } }
                    Divider()
                    SwitchRow("Hide in landscape", null, config.hideInLandscape) { v -> IslandSettings.update { it.copy(hideInLandscape = v) } }
                }
                Note("Swipe down on the island to open the notification panel, just like the status bar. The island hides while the panel is open.")

                Category("One UI tips")
                Card {
                    TipRow("Hide status bar notification icons", "Notifications › Status bar › Show notification icons: None — keeps the left of the status bar clear next to the island.") { openNotificationSettings() }
                    Divider()
                    TipRow("Avoid double pop-ups", "Notifications › Notification pop-up style: Brief, or turn pop-ups off, since the island shows them.") { openNotificationSettings() }
                    Divider()
                    TipRow("Camera cutout", "Display › Camera cutout: keep the default so the island lines up with the lens.") { startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) }
                }

                Category("Try it on the real island")
                Card { Demos() }
                Note("Adds sample activities to the island at the top of your screen. Long-press them, or hover with the S Pen.")
                Spacer(Modifier.height(48.dp))
            }

            // Collapsed One UI app bar that fades in once the big title scrolls away.
            val barAlpha = ((scroll.value - headerPx * 0.5f) / (headerPx * 0.3f)).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = barAlpha }
                    .background(c.background)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(56.dp)
                    .padding(start = 24.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text("Island Bar", style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.text))
            }
        }
    }

    /** Re-checks the battery exemption whenever the screen comes back to the front. */
    @Composable
    private fun rememberBatteryUnrestricted(): androidx.compose.runtime.State<Boolean> {
        val state = remember { mutableStateOf(isBatteryUnrestricted()) }
        val owner = LocalLifecycleOwner.current
        LaunchedEffect(owner) {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) { state.value = isBatteryUnrestricted() }
        }
        return state
    }

    private fun isBatteryUnrestricted(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    @Composable
    private fun FontPicker(selected: IslandFont) {
        val c = LocalOneUi.current
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 14.dp)) {
            Text("Island font", style = TextStyle(fontSize = 17.sp, color = c.text))
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().clip(ContinuousRoundedShape(20.dp)).background(c.chip).padding(3.dp),
            ) {
                listOf(IslandFont.IPHONE to "iPhone style", IslandFont.ONE_UI to "One UI").forEach { (font, label) ->
                    val on = font == selected
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(ContinuousRoundedShape(17.dp))
                            .background(if (on) c.card else Color.Transparent)
                            .clickable { IslandSettings.update { it.copy(font = font) } }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = TextStyle(fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) c.text else c.secondary))
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Demos() {
        val icon = remember { applicationInfo.loadIcon(packageManager) }
        FlowRow(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DemoChip(Icons.Rounded.MusicNote, Tint.Pink, "Music") { IslandRepository.upsert(demoMusic("${DEMO_PREFIX}music")) }
            DemoChip(Icons.Rounded.Timer, Tint.Orange, "Timer") { IslandRepository.upsert(demoTimer("${DEMO_PREFIX}timer")) }
            DemoChip(Icons.Rounded.Call, Tint.Green, "Call") { IslandRepository.upsert(demoCall("${DEMO_PREFIX}call", icon)) }
            DemoChip(Icons.Rounded.Download, Tint.Blue, "Download") {
                IslandRepository.upsert(
                    LiveActivity("${DEMO_PREFIX}download", ActivityType.PROGRESS, packageName, "Downloading", "holiday-photos.zip", appIcon = icon, progress = 0.62f)
                )
            }
            DemoChip(Icons.Rounded.Sms, Tint.Green, "Alert") { IslandRepository.showTransient(demoAlert(icon)) }
            DemoChip(Icons.Rounded.BatteryChargingFull, Tint.Green, "Charging") {
                IslandRepository.showTransient(TransientEvent(TransientKind.CHARGING, batteryLevel = 78, durationMs = 3000))
            }
            DemoChip(null, Tint.Red, "Clear all") { IslandRepository.removeIf { it.key.startsWith(DEMO_PREFIX) } }
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

    /** A miniature Galaxy that plays through the island's states on a loop. */
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
                .clip(ContinuousRoundedShape(26.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF14204F), Color(0xFF4C3A8F), Color(0xFFD9708C)))),
        ) {
            val density = LocalDensity.current
            val wPx = with(density) { maxWidth.roundToPx() }
            val cut = with(density) { 10.dp.roundToPx() }
            val top = with(density) { 20.dp.roundToPx() }
            val geo = remember(wPx) { IslandGeometry(wPx, wPx * 2, wPx / 2, top, cut, hasCutout = true, isLandscape = false) }
            val noop = remember { IslandCallbacks({ _, _, _ -> }, { _, _ -> }) }
            val previewConfig = config.copy(showWhenIdle = true, hideInLandscape = false)
            val bar = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
            // One UI status bar: clock on the left, system icons on the right.
            Text("9:41", style = bar, modifier = Modifier.padding(start = 22.dp, top = 11.dp))
            Text("5G  ▂▄▆  87%", style = bar, textAlign = TextAlign.End, modifier = Modifier.align(Alignment.TopEnd).padding(end = 20.dp, top = 11.dp))
            Box(Modifier.fillMaxSize().padding(top = with(density) { (top - config.heightDp.dp.roundToPx() / 2).toDp() })) {
                Island(geo, previewConfig, activities, transient, emptyFlow(), noop, requestedExpandedKey = expanded)
            }
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 20.dp - 5.dp)
                    .size(10.dp)
                    .clip(CircleShape)
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

    @SuppressLint("BatteryLife") // Sideloaded utility that must stay alive to draw the island.
    private fun requestUnrestrictedBattery() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { openAppInfo() }
    }

    private fun openNotificationSettings() {
        runCatching { startActivity(Intent("android.settings.NOTIFICATION_SETTINGS")) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }
}

// --- One UI list building blocks ---------------------------------------------

@Composable
private fun Category(text: String) {
    val c = LocalOneUi.current
    Text(
        text, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.secondary),
        modifier = Modifier.padding(start = 24.dp, top = 26.dp, bottom = 8.dp),
    )
}

@Composable
private fun Note(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val c = LocalOneUi.current
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 10.dp)) {
        Text(text, style = TextStyle(fontSize = 13.sp, color = c.secondary, lineHeight = 18.sp))
        if (action != null && onAction != null) {
            Text(
                action, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.accent),
                modifier = Modifier.padding(top = 8.dp).clickable(onClick = onAction),
            )
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val c = LocalOneUi.current
    Column(Modifier.fillMaxWidth().clip(ContinuousRoundedShape(26.dp)).background(c.card)) { content() }
}

@Composable
private fun Divider() {
    val c = LocalOneUi.current
    Box(Modifier.padding(horizontal = 24.dp).fillMaxWidth().height(1.dp).background(c.divider))
}

@Composable
private fun PermissionRow(icon: ImageVector, color: Color, title: String, subtitle: String, granted: Boolean, onClick: () -> Unit) {
    val c = LocalOneUi.current
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !granted, onClick = onClick).padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(fontSize = 17.sp, color = c.text))
            Text(
                if (granted) "On" else subtitle,
                style = TextStyle(fontSize = 14.sp, color = if (granted) c.accent else c.secondary),
            )
        }
        if (granted) {
            Icon(Icons.Rounded.CheckCircle, null, tint = c.accent, modifier = Modifier.size(24.dp))
        } else {
            Box(
                Modifier.clip(ContinuousRoundedShape(18.dp)).background(c.accent).padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("Turn on", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White))
            }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val c = LocalOneUi.current
    Column(Modifier.padding(start = 24.dp, end = 20.dp, top = 14.dp, bottom = 2.dp)) {
        Row {
            Text(label, style = TextStyle(fontSize = 17.sp, color = c.text), modifier = Modifier.weight(1f))
            Text("${value.roundToInt()} dp", style = TextStyle(fontSize = 15.sp, color = c.accent, fontFeatureSettings = "tnum"))
        }
        OneUiSeekBar(value, range, onChange)
    }
}

/** One UI seek bar: a slim rounded track and a round accent thumb with a soft halo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OneUiSeekBar(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val c = LocalOneUi.current
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val active = dragging || pressed
    Slider(
        value = value,
        onValueChange = { onChange(it.roundToInt().toFloat()) },
        valueRange = range,
        interactionSource = interaction,
        thumb = {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                if (active) Box(Modifier.size(28.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.22f)))
                Box(Modifier.size(20.dp).clip(CircleShape).background(c.accent))
            }
        },
        track = { state ->
            val fraction = ((state.value - state.valueRange.start) / (state.valueRange.endInclusive - state.valueRange.start)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.trackOff)) {
                Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(CircleShape).background(c.accent))
            }
        },
    )
}

@Composable
private fun SwitchRow(label: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalOneUi.current
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(start = 24.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = TextStyle(fontSize = 17.sp, color = c.text))
            if (summary != null) Text(summary, style = TextStyle(fontSize = 14.sp, color = c.secondary))
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = c.accent, checkedBorderColor = c.accent,
                uncheckedThumbColor = c.card, uncheckedTrackColor = c.trackOff, uncheckedBorderColor = c.trackOff,
            ),
        )
    }
}

@Composable
private fun TipRow(title: String, summary: String, onClick: () -> Unit) {
    val c = LocalOneUi.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp)) {
        Text(title, style = TextStyle(fontSize = 17.sp, color = c.text))
        Text(summary, style = TextStyle(fontSize = 14.sp, color = c.secondary, lineHeight = 19.sp))
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    val c = LocalOneUi.current
    Text(
        label, style = TextStyle(fontSize = 17.sp, color = c.accent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
    )
}

@Composable
private fun DemoChip(icon: ImageVector?, color: Color, label: String, onClick: () -> Unit) {
    val c = LocalOneUi.current
    Row(
        Modifier
            .clip(ContinuousRoundedShape(20.dp))
            .background(c.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = TextStyle(fontSize = 15.sp, color = if (icon == null) color else c.text))
    }
}
