package com.islandbar.ui.island

import android.app.PendingIntent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.islandbar.data.IslandConfig
import com.islandbar.data.IslandRepository
import com.islandbar.model.ActivityType
import com.islandbar.model.LiveActivity
import com.islandbar.model.TransientEvent
import com.islandbar.model.TransientKind
import com.islandbar.overlay.IslandGeometry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.max

class IslandCallbacks(
    /** Requested window size in px, and whether the window should take touches. */
    val onWindowChange: (width: Int, height: Int, touchable: Boolean) -> Unit,
    val open: (PendingIntent?, String?) -> Unit,
)

/** What the island is currently showing. */
sealed interface IslandMode {
    val key: String

    data object Hidden : IslandMode { override val key = "hidden" }
    data object Idle : IslandMode { override val key = "idle" }
    data class Compact(val activity: LiveActivity) : IslandMode { override val key get() = "c:${activity.key}" }
    data class Expanded(val activity: LiveActivity) : IslandMode { override val key get() = "e:${activity.key}" }
    data class Alert(val event: TransientEvent) : IslandMode { override val key get() = "a:${event.id}" }
    data class Charging(val event: TransientEvent) : IslandMode { override val key get() = "charging" }
}

data class IslandMetrics(
    val height: Dp,
    val idleWidth: Dp,
    val compactWidth: Dp,
    val expandedWidth: Dp,
    val cutout: Dp,
    /** Space at the top of expanded layouts that keeps content clear of the camera. */
    val topPad: Dp,
)

private data class PillSpec(val width: Dp, val height: Dp, val corner: Dp, val alpha: Float = 1f)

private val BubbleGap = 8.dp
private val WindowSlack = 28.dp
private const val SETTLE_MS = 750L

private fun specFor(mode: IslandMode, m: IslandMetrics): PillSpec = when (mode) {
    IslandMode.Hidden -> PillSpec(m.cutout, m.cutout, m.cutout / 2, alpha = 0f)
    IslandMode.Idle -> PillSpec(m.idleWidth, m.height, m.height / 2)
    is IslandMode.Compact, is IslandMode.Charging -> PillSpec(m.compactWidth, m.height, m.height / 2)
    is IslandMode.Expanded -> PillSpec(m.expandedWidth, m.topPad + expandedBodyHeight(mode.activity) + 18.dp, 44.dp)
    is IslandMode.Alert -> PillSpec(m.expandedWidth, m.topPad + 60.dp + 18.dp, 44.dp)
}

internal fun expandedBodyHeight(a: LiveActivity): Dp = when (a.type) {
    ActivityType.MEDIA -> 152.dp
    ActivityType.CALL -> 60.dp
    else -> if (a.actions.isNotEmpty()) 60.dp + 52.dp else 60.dp
}

@Composable
private fun rememberMetrics(geo: IslandGeometry, config: IslandConfig): IslandMetrics {
    val density = LocalDensity.current
    return remember(geo, config, density) {
        with(density) {
            val h = config.heightDp.dp
            val cut = if (geo.hasCutout) geo.cutoutSizePx.toDp() else 12.dp
            val camRadius = (cut / 2).coerceIn(5.dp, 12.dp)
            IslandMetrics(
                height = h,
                idleWidth = config.idleWidthDp.dp,
                compactWidth = config.compactWidthDp.dp,
                expandedWidth = min(geo.screenWidthPx.toDp() - 20.dp, 440.dp),
                cutout = cut.coerceIn(8.dp, 40.dp),
                topPad = if (geo.hasCutout) h / 2 + camRadius + 6.dp else 14.dp,
            )
        }
    }
}

@Composable
fun Island(
    geometry: IslandGeometry,
    config: IslandConfig,
    activities: List<LiveActivity>,
    transient: TransientEvent?,
    outsideTouches: Flow<Unit>,
    callbacks: IslandCallbacks,
    initialExpandedKey: String? = null,
) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        IslandContent(geometry, config, activities, transient, outsideTouches, callbacks, initialExpandedKey)
    }
}

@Composable
private fun IslandContent(
    geometry: IslandGeometry,
    config: IslandConfig,
    activities: List<LiveActivity>,
    transient: TransientEvent?,
    outsideTouches: Flow<Unit>,
    callbacks: IslandCallbacks,
    initialExpandedKey: String?,
) {
    val metrics = rememberMetrics(geometry, config)
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var preferredKey by remember { mutableStateOf<String?>(null) }
    var expandedKey by remember { mutableStateOf(initialExpandedKey) }

    val primary = activities.firstOrNull { it.key == preferredKey } ?: activities.firstOrNull()
    val secondary = activities.firstOrNull { it.key != primary?.key }

    val hide = config.hideInLandscape && geometry.isLandscape
    val mode: IslandMode = when {
        hide -> IslandMode.Hidden
        transient?.kind == TransientKind.ALERT -> IslandMode.Alert(transient)
        primary != null && expandedKey == primary.key -> IslandMode.Expanded(primary)
        transient?.kind == TransientKind.CHARGING -> IslandMode.Charging(transient)
        primary != null -> IslandMode.Compact(primary)
        config.showWhenIdle -> IslandMode.Idle
        else -> IslandMode.Hidden
    }
    val showBubble = mode is IslandMode.Compact && secondary != null

    // Drop the expanded state if its activity ends.
    LaunchedEffect(activities) {
        if (expandedKey != null && activities.none { it.key == expandedKey }) expandedKey = null
    }

    // Transient events time out on their own.
    LaunchedEffect(transient?.id) {
        val t = transient ?: return@LaunchedEffect
        delay(t.durationMs)
        IslandRepository.dismissTransient(t.id)
    }
    // An expanded island folds back after a while, as on iPhone.
    LaunchedEffect(expandedKey) {
        if (expandedKey != null) {
            delay(10_000)
            expandedKey = null
        }
    }
    val currentTransient by rememberUpdatedState(transient)
    LaunchedEffect(outsideTouches) {
        outsideTouches.collect {
            expandedKey = null
            currentTransient?.takeIf { it.kind == TransientKind.ALERT }
                ?.let { IslandRepository.dismissTransient(it.id) }
        }
    }

    // ---- Shape animation -------------------------------------------------
    val spec = specFor(mode, metrics)
    val bouncy = spring<Dp>(dampingRatio = 0.7f, stiffness = 380f)
    val width by animateDpAsState(spec.width, bouncy, label = "w")
    val height by animateDpAsState(spec.height, bouncy, label = "h")
    val corner by animateDpAsState(spec.corner, spring(stiffness = 380f), label = "r")
    val alpha by animateFloatAsState(spec.alpha, tween(220), label = "a")
    val squish = remember { Animatable(1f) }

    // ---- Window sizing: grow first, shrink after the spring settles ------
    val bubbleExtra = if (showBubble) (BubbleGap + metrics.height) * 2 else 0.dp
    val targetW = with(density) { (spec.width + bubbleExtra + WindowSlack).roundToPx() }
    val targetH = with(density) { (spec.height + WindowSlack).roundToPx() }
    val touchable = mode != IslandMode.Hidden
    val lastWindow = remember { intArrayOf(0, 0) }
    LaunchedEffect(targetW, targetH, touchable) {
        val growW = max(targetW, lastWindow[0])
        val growH = max(targetH, lastWindow[1])
        callbacks.onWindowChange(growW, growH, touchable)
        lastWindow[0] = growW; lastWindow[1] = growH
        delay(SETTLE_MS)
        callbacks.onWindowChange(targetW, targetH, touchable)
        lastWindow[0] = targetW; lastWindow[1] = targetH
    }

    // ---- Gestures ---------------------------------------------------------
    val currentMode by rememberUpdatedState(mode)
    val onTap: () -> Unit = {
        when (val m = currentMode) {
            is IslandMode.Compact -> callbacks.open(m.activity.contentIntent, m.activity.packageName)
            is IslandMode.Expanded -> {
                callbacks.open(m.activity.contentIntent, m.activity.packageName)
                expandedKey = null
            }
            is IslandMode.Alert -> {
                callbacks.open(m.event.contentIntent, m.event.packageName.ifBlank { null })
                IslandRepository.dismissTransient(m.event.id)
            }
            else -> scope.launch {
                squish.animateTo(1.07f, spring(stiffness = Spring.StiffnessHigh))
                squish.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f))
            }
        }
    }
    val onLongPress: () -> Unit = {
        when (val m = currentMode) {
            is IslandMode.Compact -> {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                expandedKey = m.activity.key
            }
            else -> Unit
        }
    }
    val onSwipe: (Float) -> Unit = { dy ->
        when (val m = currentMode) {
            is IslandMode.Expanded -> if (dy < 0) expandedKey = null
            is IslandMode.Alert -> if (dy < 0) IslandRepository.dismissTransient(m.event.id)
            is IslandMode.Compact -> if (dy > 0) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                expandedKey = m.activity.key
            }
            else -> Unit
        }
    }
    val swipeThreshold = with(density) { 24.dp.toPx() }

    // ---- Layout -----------------------------------------------------------
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .size(width, height)
                .graphicsLayer {
                    this.alpha = alpha
                    scaleX = squish.value
                    scaleY = squish.value
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    shape = RoundedCornerShape(corner.coerceAtLeast(0.dp))
                    clip = true
                }
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
                }
                .pointerInput(Unit) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = { if (kotlin.math.abs(total) > swipeThreshold) onSwipe(total) },
                        onVerticalDrag = { change, dy -> change.consume(); total += dy },
                    )
                }
        ) {
            Box(
                Modifier.wrapContentSize(align = Alignment.TopCenter, unbounded = true)
            ) {
                AnimatedContent(
                    targetState = mode,
                    contentKey = { it.key },
                    contentAlignment = Alignment.TopCenter,
                    transitionSpec = {
                        (fadeIn(tween(220, delayMillis = 90)) + scaleIn(tween(320), initialScale = 0.9f))
                            .togetherWith(fadeOut(tween(110)) + scaleOut(tween(160), targetScale = 0.95f))
                            .using(SizeTransform(clip = false))
                    },
                    label = "content",
                ) { m ->
                    val s = specFor(m, metrics)
                    Box(Modifier.size(s.width, s.height)) {
                        when (m) {
                            IslandMode.Hidden, IslandMode.Idle -> Unit
                            is IslandMode.Compact -> CompactContent(m.activity)
                            is IslandMode.Charging -> ChargingContent(m.event)
                            is IslandMode.Expanded -> ExpandedContent(m.activity, metrics, callbacks)
                            is IslandMode.Alert -> AlertContent(m.event, metrics)
                        }
                    }
                }
            }
        }

        // The detached circle for a second live activity.
        // Remember the last secondary so the bubble can animate out with its content.
        val lastSecondary = remember { mutableStateOf<LiveActivity?>(null) }
        SideEffect { if (secondary != null) lastSecondary.value = secondary }
        val bubbleActivity = secondary ?: lastSecondary.value
        AnimatedVisibility(
            visible = showBubble,
            enter = fadeIn(tween(200, delayMillis = 120)) + scaleIn(spring(0.55f, 400f), initialScale = 0.3f),
            exit = fadeOut(tween(120)) + scaleOut(tween(160), targetScale = 0.3f),
            modifier = Modifier.offset {
                IntOffset((width / 2 + BubbleGap + metrics.height / 2).roundToPx(), 0)
            },
        ) {
            Box(
                Modifier
                    .size(metrics.height)
                    .graphicsLayer { shape = CircleShape; clip = true }
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { lastSecondary.value?.let { preferredKey = it.key } },
                            onLongPress = {
                                lastSecondary.value?.let {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    preferredKey = it.key
                                    expandedKey = it.key
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                bubbleActivity?.let { BubbleContent(it) }
            }
        }
    }
}
