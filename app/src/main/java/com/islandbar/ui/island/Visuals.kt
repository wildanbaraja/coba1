package com.islandbar.ui.island

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import com.islandbar.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

internal object IslandColors {
    val Green = Color(0xFF32D74B)
    val Orange = Color(0xFFFF9F0A)
    val Red = Color(0xFFFF453A)
    val Blue = Color(0xFF0A84FF)
    val Purple = Color(0xFFBF5AF2)
    val Gray = Color(0xFF98989F)
    val Gray2 = Color(0xFF636366)
    val Chip = Color(0xFF2C2C2E)
    val Track = Color(0x33FFFFFF)
}

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Text styles tuned to feel like Apple's system UI (tight tracking, tabular digits). */
internal object IslandType {
    val title = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = (-0.2).sp, color = Color.White)
    val subtitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, letterSpacing = (-0.1).sp, color = IslandColors.Gray)
    val caption = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp, color = IslandColors.Gray, fontFeatureSettings = "tnum")
    val compact = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = (-0.2).sp, fontFeatureSettings = "tnum")
    val big = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 34.sp, letterSpacing = (-0.8).sp, fontFeatureSettings = "tnum")
    val button = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
}

// ---------------------------------------------------------------------------
// Reveal: content of each island state animates 0→1 on enter, 1→0 on exit.
// Children read it to slide away from the camera / stagger in.
// ---------------------------------------------------------------------------

internal val LocalReveal = staticCompositionLocalOf<() -> Float> { { 1f } }

/** Stagger: item [index] starts its reveal a little after the previous one. */
internal fun Modifier.revealItem(index: Int, rise: Dp = 10.dp): Modifier = composed {
    val reveal = LocalReveal.current
    val risePx = with(LocalDensity.current) { rise.toPx() }
    graphicsLayer {
        val start = index * 0.09f
        val r = ((reveal() - start) / (1f - start)).coerceIn(0f, 1f)
        alpha = r
        translationY = (1f - r) * risePx
        val s = 0.92f + 0.08f * r
        scaleX = s; scaleY = s
    }
}

/** Compact side items glide out from under the camera. [direction] = -1 left, +1 right. */
internal fun Modifier.revealFromCenter(direction: Int, distance: Dp = 26.dp): Modifier = composed {
    val reveal = LocalReveal.current
    val px = with(LocalDensity.current) { distance.toPx() }
    graphicsLayer {
        val r = reveal()
        alpha = r
        translationX = -direction * (1f - r) * px
        val s = 0.6f + 0.4f * r
        scaleX = s; scaleY = s
    }
}

/** iOS-style press: shrink with a spring while held, bounce back on release. */
internal fun Modifier.pressable(onClick: () -> Unit, pressedScale: Float = 0.86f): Modifier = composed {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    this
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .pointerInput(onClick) {
            detectTapGestures(
                onPress = {
                    scope.launch { scale.animateTo(pressedScale, spring(stiffness = 1200f)) }
                    tryAwaitRelease()
                    scope.launch { scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 600f)) }
                },
                onTap = { onClick() },
            )
        }
}

// ---------------------------------------------------------------------------

@Composable
internal fun rememberImage(drawable: Drawable?, sizePx: Int = 144): ImageBitmap? = remember(drawable) {
    drawable?.let { runCatching { it.toBitmap(sizePx, sizePx).asImageBitmap() }.getOrNull() }
}

@Composable
internal fun rememberImage(bitmap: Bitmap?): ImageBitmap? = remember(bitmap) { bitmap?.asImageBitmap() }

/** A vivid colour pulled from album art, used to tint the waveform like iOS does. */
@Composable
internal fun rememberAccent(bitmap: Bitmap?, fallback: Color): Color = remember(bitmap, fallback) {
    bitmap?.let {
        runCatching {
            val p = Palette.from(it).maximumColorCount(16).generate()
            val sw = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.dominantSwatch
            sw?.rgb?.let { argb -> brighten(Color(argb)) }
        }.getOrNull()
    } ?: fallback
}

/** Keeps very dark artwork colours readable on the black island. */
private fun brighten(c: Color): Color {
    val lum = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
    if (lum >= 0.45f) return c
    val t = (0.45f - lum) / 0.45f * 0.6f
    return Color(c.red + (1 - c.red) * t, c.green + (1 - c.green) * t, c.blue + (1 - c.blue) * t)
}

@Composable
internal fun rememberNow(periodMs: Long = 1000): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(periodMs - (value % periodMs))
    }
}

internal fun formatDuration(ms: Long): String {
    val s = abs(ms) / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
internal fun ChronoText(base: Long, countDown: Boolean, style: TextStyle, color: Color) {
    val now by rememberNow()
    val ms = if (countDown) base - now else now - base
    Text(formatDuration(ms.coerceAtLeast(0)), style = style, color = color, maxLines = 1)
}

/**
 * Now-playing waveform: rounded bars, centred vertically, driven by layered
 * sine "noise" so the motion feels organic rather than looping. Settles to
 * small dots when paused.
 */
@Composable
internal fun Waveform(playing: Boolean, color: Color, modifier: Modifier = Modifier, bars: Int = 5) {
    val energy by animateFloatAsState(if (playing) 1f else 0f, tween(450), label = "energy")
    val time = remember { mutableFloatStateOf(0f) }
    if (playing || energy > 0f) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (true) withFrameNanos { time.floatValue = (it - start) / 1_000_000_000f }
        }
    }
    Canvas(modifier) {
        val gap = size.width * 0.12f
        val barW = (size.width - gap * (bars - 1)) / bars
        val t = time.floatValue
        for (i in 0 until bars) {
            val phase = i * 1.7f
            val n = (sin(t * 7.3f + phase) * 0.5f + sin(t * 3.1f + phase * 2.3f) * 0.3f + sin(t * 11.7f + phase * 0.7f) * 0.2f)
            val envelope = if (i == 0 || i == bars - 1) 0.7f else 1f
            val level = 0.18f + energy * envelope * (0.35f + 0.47f * abs(n))
            val h = (size.height * level).coerceAtLeast(barW)
            val x = i * (barW + gap)
            drawRoundRect(
                color = color,
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

@Composable
internal fun ProgressRing(progress: Float, color: Color, modifier: Modifier = Modifier, stroke: Dp = 3.dp) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), spring(stiffness = 120f), label = "ring")
    Canvas(modifier) {
        val w = stroke.toPx()
        val inset = w / 2
        val arcSize = Size(size.width - w, size.height - w)
        val topLeft = Offset(inset, inset)
        drawArc(IslandColors.Track, 0f, 360f, false, topLeft, arcSize, style = Stroke(w))
        drawArc(color, -90f, 360f * animated, false, topLeft, arcSize, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** An iOS-like battery glyph with charge level and bolt. */
@Composable
internal fun BatteryGlyph(level: Int, color: Color, modifier: Modifier = Modifier) {
    val fill by animateFloatAsState((level / 100f).coerceIn(0.05f, 1f), tween(900), label = "battery")
    Canvas(modifier) {
        val capW = size.width * 0.07f
        val bodyW = size.width - capW - size.width * 0.03f
        val r = size.height * 0.3f
        val stroke = size.height * 0.09f
        drawRoundRect(color.copy(alpha = 0.45f), Offset(stroke / 2, stroke / 2), Size(bodyW - stroke, size.height - stroke), CornerRadius(r), style = Stroke(stroke))
        val pad = stroke * 1.9f
        drawRoundRect(
            color, Offset(pad, pad),
            Size((bodyW - pad * 2) * fill, size.height - pad * 2), CornerRadius(r * 0.55f),
        )
        drawRoundRect(color.copy(alpha = 0.45f), Offset(size.width - capW, size.height * 0.33f), Size(capW, size.height * 0.34f), CornerRadius(capW))
        // bolt
        val cx = bodyW / 2f; val h = size.height
        val bolt = Path().apply {
            moveTo(cx + h * 0.08f, h * 0.12f)
            lineTo(cx - h * 0.2f, h * 0.56f)
            lineTo(cx - h * 0.01f, h * 0.56f)
            lineTo(cx - h * 0.08f, h * 0.88f)
            lineTo(cx + h * 0.2f, h * 0.44f)
            lineTo(cx + h * 0.01f, h * 0.44f)
            close()
        }
        drawPath(bolt, Color.Black)
    }
}

/** Album art with a hairline edge, or a gradient placeholder. */
@Composable
internal fun Artwork(art: Bitmap?, size: Dp, corner: Dp) {
    val shape = ContinuousRoundedShape(corner)
    val image = rememberImage(art)
    val mod = Modifier.size(size).clip(shape).border(0.5.dp, Color(0x26FFFFFF), shape)
    if (image != null) {
        Image(image, null, mod, contentScale = ContentScale.Crop)
    } else {
        Box(
            Modifier.size(size).clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xFFFF375F), Color(0xFFBF5AF2), Color(0xFF5E5CE6)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
        }
    }
}

@Composable
internal fun AppIcon(drawable: Drawable?, size: Dp, shape: Shape, fallback: Color = IslandColors.Gray2) {
    val image = rememberImage(drawable)
    if (image != null) {
        Image(image, null, Modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.size(size).clip(shape).background(fallback))
    }
}

/** A tinted circular glyph, like the symbols in iOS Live Activities. */
@Composable
internal fun GlyphCircle(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, size: Dp, iconSize: Dp = size * 0.5f) {
    Box(Modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape).background(tint.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
internal fun SingleLine(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified, size: TextUnit = TextUnit.Unspecified) {
    Text(
        text, style = style, color = color, fontSize = size, maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = modifier,
    )
}
