package com.islandbar.ui.island

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import kotlinx.coroutines.delay
import kotlin.math.abs

internal object IslandColors {
    val Green = Color(0xFF30D158)
    val Orange = Color(0xFFFF9F0A)
    val Red = Color(0xFFFF453A)
    val Blue = Color(0xFF0A84FF)
    val Gray = Color(0xFF8E8E93)
    val Chip = Color(0xFF2C2C2E)
    val Track = Color(0xFF3A3A3C)
}

internal val Tabular = TextStyle(fontFeatureSettings = "tnum")

@Composable
internal fun rememberImage(drawable: Drawable?, sizePx: Int = 128): ImageBitmap? = remember(drawable) {
    drawable?.let { runCatching { it.toBitmap(sizePx, sizePx).asImageBitmap() }.getOrNull() }
}

@Composable
internal fun rememberImage(bitmap: Bitmap?): ImageBitmap? = remember(bitmap) { bitmap?.asImageBitmap() }

/** A vivid colour pulled from album art, used to tint the waveform like iOS does. */
@Composable
internal fun rememberAccent(bitmap: Bitmap?, fallback: Color): Color = remember(bitmap, fallback) {
    bitmap?.let {
        runCatching {
            val p = Palette.from(it).maximumColorCount(12).generate()
            val argb = p.getVibrantColor(p.getLightVibrantColor(p.getDominantColor(0)))
            if (argb == 0) null else Color(argb)
        }.getOrNull()
    } ?: fallback
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
internal fun ChronoText(
    base: Long,
    countDown: Boolean,
    color: Color,
    fontSize: TextUnit,
    weight: FontWeight = FontWeight.SemiBold,
) {
    val now by rememberNow()
    val ms = if (countDown) base - now else now - base
    Text(
        formatDuration(ms.coerceAtLeast(0)),
        color = color,
        fontSize = fontSize,
        fontWeight = weight,
        style = Tabular,
        maxLines = 1,
    )
}

/** Bouncing bars, like the iPhone's now-playing waveform. */
@Composable
internal fun Equalizer(playing: Boolean, color: Color, modifier: Modifier = Modifier, bars: Int = 4) {
    val transition = rememberInfiniteTransition(label = "eq")
    val durations = intArrayOf(420, 560, 360, 500, 460)
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(bars) { i ->
            val level by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(durations[i % durations.size], easing = FastOutSlowInEasing),
                    RepeatMode.Reverse,
                ),
                label = "bar$i",
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(if (playing) level else 0.22f)
                    .clip(RoundedCornerShape(50))
                    .background(color)
            )
        }
    }
}

@Composable
internal fun ProgressRing(progress: Float, color: Color, modifier: Modifier = Modifier, stroke: Dp = 3.dp) {
    Canvas(modifier) {
        val w = stroke.toPx()
        val inset = w / 2
        val arcSize = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(IslandColors.Track, 0f, 360f, false, topLeft, arcSize, style = Stroke(w))
        drawArc(color, -90f, 360f * progress.coerceIn(0f, 1f), false, topLeft, arcSize, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** Album art with a gradient placeholder. */
@Composable
internal fun Artwork(art: Bitmap?, size: Dp, shape: Shape) {
    val image = rememberImage(art)
    if (image != null) {
        Image(image, null, Modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
    } else {
        Box(
            Modifier
                .size(size)
                .clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xFFFF375F), Color(0xFF5E5CE6)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
        }
    }
}

@Composable
internal fun AppIcon(drawable: Drawable?, size: Dp, shape: Shape, fallbackTint: Color = IslandColors.Gray) {
    val image = rememberImage(drawable)
    if (image != null) {
        Image(image, null, Modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.size(size).clip(shape).background(fallbackTint))
    }
}
