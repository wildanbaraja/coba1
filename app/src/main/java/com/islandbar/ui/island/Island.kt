package com.islandbar.ui.island

import android.app.PendingIntent
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
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
import com.islandbar.data.IslandFont
import com.islandbar.data.SystemUiState
import com.islandbar.data.IslandRepository
import com.islandbar.model.ActivityType
import com.islandbar.model.LiveActivity
import com.islandbar.model.TransientEvent
import com.islandbar.model.TransientKind
import com.islandbar.overlay.IslandGeometry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sign

class IslandCallbacks(
    /** Requested window size in px, and whether the window should take touches. */
    val onWindowChange: (width: Int, height: Int, touchable: Boolean) -> Unit,
    val open: (PendingIntent?, String?) -> Unit,
    /** Pulls down One UI's notification panel (the island sits where that gesture starts). */
    val openNotificationShade: () -> Unit = {},
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

// ---- Motion tuning ---------------------------------------------------------
// Expanding: the height is a touch softer than the width, so the island first
// widens then "drops" open with a gentle overshoot. Collapsing is quicker and
// almost critically damped, which is what makes iOS feel snappy on the way back.
private val ExpandWidth = spring<Dp>(dampingRatio = 0.74f, stiffness = 300f)
private val ExpandHeight = spring<Dp>(dampingRatio = 0.68f, stiffness = 230f)
private val CollapseWidth = spring<Dp>(dampingRatio = 0.86f, stiffness = 360f)
private val CollapseHeight = spring<Dp>(dampingRatio = 0.9f, stiffness = 460f)
private val CornerSpring = spring<Dp>(dampingRatio = 1f, stiffness = 320f)
private val EaseOutQuint = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

private val BubbleGap = 7.dp
private val WindowSlack = 40.dp
private val MaxStretch = 16.dp
private const val SETTLE_MS = 1200L
private const val ContentBlurDp = 14f

private fun specFor(mode: IslandMode, m: IslandMetrics): PillSpec = when (mode) {
    IslandMode.Hidden -> PillSpec(m.cutout, m.cutout, m.cutout / 2, alpha = 0f)
    IslandMode.Idle -> PillSpec(m.idleWidth, m.height, m.height / 2)
    is IslandMode.Compact, is IslandMode.Charging -> PillSpec(m.compactWidth, m.height, m.height / 2)
    is IslandMode.Expanded -> PillSpec(m.expandedWidth, m.topPad + expandedBodyHeight(mode.activity) + 20.dp, 46.dp)
    is IslandMode.Alert -> PillSpec(m.expandedWidth, m.topPad + 60.dp + 20.dp, 46.dp)
}

internal fun expandedBodyHeight(a: LiveActivity): Dp = when (a.type) {
    ActivityType.MEDIA -> 140.dp
    ActivityType.CALL -> 60.dp
    ActivityType.TIMER -> if (timerNeedsChips(a)) 64.dp + 52.dp else 64.dp
    else -> if (a.actions.isNotEmpty()) 60.dp + 54.dp else 60.dp
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
                expandedWidth = min(geo.screenWidthPx.toDp() - 18.dp, 440.dp),
                cutout = cut.coerceIn(8.dp, 40.dp),
                topPad = if (geo.hasCutout) h / 2 + camRadius + 6.dp else 16.dp,
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
    requestedExpandedKey: String? = null,
    systemUi: SystemUiState = SystemUiState(),
) {
    val font = if (config.font == IslandFont.IPHONE) Inter else FontFamily.Default
    MaterialTheme(colorScheme = darkColorScheme()) {
        ProvideTextStyle(TextStyle(fontFamily = font)) {
            IslandContent(geometry, config, activities, transient, outsideTouches, callbacks, requestedExpandedKey, systemUi)
        }
    }
}

/** Animates a Dp towards [target], picking the spring by direction. */
@Composable
private fun rememberMorph(target: Dp, grow: SpringSpec<Dp>, shrink: SpringSpec<Dp>): Animatable<Dp, *> {
    val anim = remember { Animatable(target, Dp.VectorConverter) }
    LaunchedEffect(target) {
        anim.animateTo(target, if (target > anim.value) grow else shrink)
    }
    return anim
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun IslandContent(
    geometry: IslandGeometry,
    config: IslandConfig,
    activities: List<LiveActivity>,
    transient: TransientEvent?,
    outsideTouches: Flow<Unit>,
    callbacks: IslandCallbacks,
    requestedExpandedKey: String?,
    systemUi: SystemUiState,
) {
    val metrics = rememberMetrics(geometry, config)
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var preferredKey by remember { mutableStateOf<String?>(null) }
    var expandedKey by remember { mutableStateOf(requestedExpandedKey) }
    LaunchedEffect(requestedExpandedKey) { expandedKey = requestedExpandedKey }

    val primary = activities.firstOrNull { it.key == preferredKey } ?: activities.firstOrNull()
    val secondary = activities.firstOrNull { it.key != primary?.key }

    // Step aside like One UI's own status bar does.
    val hide = (config.hideInLandscape && geometry.isLandscape) ||
        systemUi.shadeOpen ||
        (config.hideInFullscreen && systemUi.fullscreen)
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
    val isBig = mode is IslandMode.Expanded || mode is IslandMode.Alert

    // ---- Lifecycle of states ---------------------------------------------
    LaunchedEffect(activities) {
        if (expandedKey != null && activities.none { it.key == expandedKey }) expandedKey = null
    }
    LaunchedEffect(transient?.id) {
        val t = transient ?: return@LaunchedEffect
        delay(t.durationMs)
        IslandRepository.dismissTransient(t.id)
    }
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

    // ---- Shape morph -----------------------------------------------------
    val spec = specFor(mode, metrics)
    val width = rememberMorph(spec.width, ExpandWidth, CollapseWidth)
    val height = rememberMorph(spec.height, ExpandHeight, CollapseHeight)
    val corner = rememberMorph(spec.corner, CornerSpring, CornerSpring)
    val pillAlpha by animateFloatAsState(spec.alpha, tween(260), label = "alpha")
    val elevation by animateDpAsState(if (isBig) 22.dp else 0.dp, tween(450), label = "elev")

    // Touch feedback: press "inflates" the island; idle tap jiggles it; drag stretches it.
    val press = remember { Animatable(1f) }
    val jiggle = remember { Animatable(0f) }
    val stretch = remember { Animatable(0f) } // px, positive = pulled down

    // Second-activity bubble: 0 = merged into the pill, 1 = fully separated.
    val bubble = remember { Animatable(if (showBubble) 1f else 0f) }
    LaunchedEffect(showBubble) {
        if (showBubble) {
            delay(140)
            bubble.animateTo(1f, spring(dampingRatio = 0.56f, stiffness = 180f))
        } else {
            bubble.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 420f))
        }
    }

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
    fun expand(a: LiveActivity) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        expandedKey = a.key
    }
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
                jiggle.snapTo(0f)
                jiggle.animateTo(1f, spring(stiffness = 2000f))
                jiggle.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 420f))
            }
        }
    }
    val onLongPress: () -> Unit = {
        (currentMode as? IslandMode.Compact)?.let { expand(it.activity) }
    }
    // Swiping down from the top edge is how One UI opens the notification panel,
    // so wherever the island has nothing to expand, hand that gesture to the system.
    val onSwipe: (Float) -> Unit = { dy ->
        when (val m = currentMode) {
            is IslandMode.Expanded -> if (dy < 0) expandedKey = null else callbacks.openNotificationShade()
            is IslandMode.Alert -> {
                IslandRepository.dismissTransient(m.event.id)
                if (dy > 0) callbacks.openNotificationShade()
            }
            is IslandMode.Compact -> if (dy > 0) expand(m.activity)
            IslandMode.Idle, is IslandMode.Charging -> if (dy > 0) callbacks.openNotificationShade()
            IslandMode.Hidden -> Unit
        }
    }

    // S Pen: hovering over a live activity peeks at its expanded view (like Air view);
    // moving the pen away folds it back.
    var hoverPeekKey by remember { mutableStateOf<String?>(null) }
    var hovering by remember { mutableStateOf(false) }
    LaunchedEffect(hovering, config.sPenHover) {
        if (!config.sPenHover) return@LaunchedEffect
        if (hovering) {
            delay(380)
            (currentMode as? IslandMode.Compact)?.let {
                hoverPeekKey = it.activity.key
                expand(it.activity)
            }
        } else if (hoverPeekKey != null) {
            delay(650)
            if (expandedKey == hoverPeekKey) expandedKey = null
            hoverPeekKey = null
        }
    }
    val swipeThreshold = with(density) { 28.dp.toPx() }
    val maxStretchPx = with(density) { MaxStretch.toPx() }
    fun rubber(x: Float): Float {
        val r = maxStretchPx * (1f - exp(-abs(x) / (maxStretchPx * 2.2f)))
        return sign(x) * if (x < 0) r * 0.35f else r
    }
    val releaseSpring = spring<Float>(dampingRatio = 0.42f, stiffness = 380f)

    // ---- Layout -----------------------------------------------------------
    val pillShapeCorner = corner.value.coerceAtLeast(0.dp)
    val stretchPx = stretch.value
    val pillWidth = width.value + with(density) { (stretchPx * 0.45f).toDp() }
    val pillHeight = height.value + with(density) { stretchPx.toDp() }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val j = jiggle.value
                scaleX = press.value * (1f + 0.08f * j)
                scaleY = press.value * (1f - 0.05f * j)
                transformOrigin = TransformOrigin(0.5f, 0f)
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        // Gooey separation of the second activity.
        val bridgePath = remember { Path() }
        Canvas(Modifier.fillMaxSize()) {
            val p = bubble.value
            if (p <= 0.001f) return@Canvas
            val r1 = metrics.height.toPx() / 2f
            val r2 = r1 * (0.5f + 0.5f * p.coerceIn(0f, 1f))
            val cx = size.width / 2f
            val half = pillWidth.toPx() / 2f
            val c1 = Offset(cx + half - r1, r1)
            val startX = cx + half - r1
            val endX = cx + half + BubbleGap.toPx() + r1
            val c2 = Offset(startX + (endX - startX) * p, r1)
            drawCircle(Color.Black, r2, c2)
            val d = hypot(c2.x - c1.x, c2.y - c1.y)
            val snap = r1 + r2 + BubbleGap.toPx() * 0.85f
            val v = 0.5f * (1f - ((d - (r1 + r2) * 0.8f) / (snap - (r1 + r2) * 0.8f)).coerceIn(0f, 1f))
            metaballBridge(c1, r1, c2, r2, v, path = bridgePath)?.let { drawPath(it, Color.Black) }
        }

        // The island itself.
        Box(
            Modifier
                .size(pillWidth, pillHeight)
                .graphicsLayer {
                    alpha = pillAlpha
                    shape = ContinuousRoundedShape(pillShapeCorner)
                    clip = true
                    shadowElevation = elevation.toPx()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        spotShadowColor = Color.Black
                        ambientShadowColor = Color.Black
                    }
                }
                .background(Color.Black)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            val stylus = e.changes.any { it.type == PointerType.Stylus }
                            when {
                                stylus && e.type == PointerEventType.Enter -> hovering = true
                                stylus && e.type == PointerEventType.Exit -> hovering = false
                                e.type == PointerEventType.Press -> hoverPeekKey = null // a real tap/press takes over
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            if (currentMode !is IslandMode.Hidden) {
                                scope.launch { press.animateTo(1.045f, spring(dampingRatio = 0.7f, stiffness = 500f)) }
                                tryAwaitRelease()
                                scope.launch { press.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 420f)) }
                            }
                        },
                        onTap = { onTap() },
                        onLongPress = { onLongPress() },
                    )
                }
                .pointerInput(Unit) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            if (abs(total) > swipeThreshold) onSwipe(total)
                            scope.launch { stretch.animateTo(0f, releaseSpring) }
                        },
                        onDragCancel = { scope.launch { stretch.animateTo(0f, releaseSpring) } },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            total += dy
                            scope.launch { stretch.snapTo(rubber(total)) }
                        },
                    )
                }
        ) {
            Box(Modifier.wrapContentSize(align = Alignment.TopCenter, unbounded = true)) {
                AnimatedContent(
                    targetState = mode,
                    contentKey = { it.key },
                    contentAlignment = Alignment.TopCenter,
                    transitionSpec = {
                        EnterTransition.None.togetherWith(fadeOut(tween(170)))
                            .using(SizeTransform(clip = false))
                    },
                    label = "content",
                ) { m ->
                    val reveal = transition.animateFloat(
                        transitionSpec = {
                            if (targetState == EnterExitState.Visible) tween(520, delayMillis = 60, easing = EaseOutQuint)
                            else tween(170)
                        },
                        label = "reveal",
                    ) { if (it == EnterExitState.Visible) 1f else 0f }
                    val s = specFor(m, metrics)
                    val big = m is IslandMode.Expanded || m is IslandMode.Alert
                    Box(
                        Modifier
                            .size(s.width, s.height)
                            .graphicsLayer {
                                val r = reveal.value
                                alpha = (r * 1.6f).coerceAtMost(1f)
                                if (big) {
                                    val sc = 0.9f + 0.1f * r
                                    scaleX = sc; scaleY = sc
                                    transformOrigin = TransformOrigin(0.5f, 0f)
                                }
                                val blur = (1f - r) * ContentBlurDp.dp.toPx()
                                renderEffect = if (blur > 0.5f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    BlurEffect(blur, blur, TileMode.Decal)
                                } else null
                            }
                    ) {
                        CompositionLocalProvider(LocalReveal provides { reveal.value }) {
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
        }

        // Content and touch target for the bubble (its black disc is drawn by the Canvas above).
        val lastSecondary = remember { mutableStateOf<LiveActivity?>(null) }
        SideEffect { if (secondary != null) lastSecondary.value = secondary }
        val bubbleActivity = secondary ?: lastSecondary.value
        if (bubble.value > 0.001f && bubbleActivity != null) {
            Box(
                Modifier
                    .offset {
                        val p = bubble.value
                        val r1 = metrics.height.toPx() / 2f
                        val half = pillWidth.toPx() / 2f
                        val startX = half - r1
                        val endX = half + BubbleGap.toPx() + r1
                        IntOffset((startX + (endX - startX) * p).roundToInt(), 0)
                    }
                    .size(metrics.height)
                    .graphicsLayer {
                        val p = bubble.value
                        alpha = ((p - 0.45f) / 0.55f).coerceIn(0f, 1f)
                        val sc = 0.5f + 0.5f * p.coerceIn(0f, 1f)
                        scaleX = sc; scaleY = sc
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { lastSecondary.value?.let { preferredKey = it.key } },
                            onLongPress = {
                                lastSecondary.value?.let {
                                    preferredKey = it.key
                                    expand(it)
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                BubbleContent(bubbleActivity)
            }
        }
    }
}
