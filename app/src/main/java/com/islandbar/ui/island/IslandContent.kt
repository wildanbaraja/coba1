package com.islandbar.ui.island

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.islandbar.data.IslandRepository
import com.islandbar.model.ActionKind
import com.islandbar.model.ActivityType
import com.islandbar.model.IslandAction
import com.islandbar.model.LiveActivity
import com.islandbar.model.TransientEvent

private fun typeIcon(type: ActivityType): ImageVector = when (type) {
    ActivityType.CALL -> Icons.Rounded.Call
    ActivityType.NAVIGATION -> Icons.Rounded.Navigation
    ActivityType.TIMER -> Icons.Rounded.Timer
    ActivityType.MEDIA -> Icons.Rounded.PlayArrow
    ActivityType.PROGRESS -> Icons.Rounded.Download
    ActivityType.LIVE -> Icons.Rounded.NotificationsActive
}

private fun typeColor(a: LiveActivity): Color = when (a.type) {
    ActivityType.CALL -> IslandColors.Green
    ActivityType.NAVIGATION -> IslandColors.Blue
    ActivityType.TIMER -> IslandColors.Orange
    ActivityType.MEDIA -> Color.White
    ActivityType.PROGRESS -> IslandColors.Blue
    ActivityType.LIVE -> if (a.accentColor != 0) Color(a.accentColor).copy(alpha = 1f) else IslandColors.Purple
}

/** Maps a notification action title to an SF-Symbols-like glyph, if we recognise it. */
private fun actionGlyph(title: String): ImageVector? {
    val t = title.lowercase()
    return when {
        listOf("pause", "jeda").any { it in t } -> Icons.Rounded.Pause
        listOf("resume", "continue", "start", "play", "lanjut", "mulai").any { it in t } -> Icons.Rounded.PlayArrow
        listOf("cancel", "stop", "dismiss", "delete", "batal", "hapus", "berhenti", "tutup").any { it in t } -> Icons.Rounded.Close
        listOf("reset", "restart", "ulang").any { it in t } -> Icons.Rounded.Refresh
        listOf("lap", "putaran").any { it in t } -> Icons.Rounded.Flag
        listOf("add", "+", "tambah").any { it in t } -> Icons.Rounded.Add
        else -> null
    }
}

internal fun timerNeedsChips(a: LiveActivity): Boolean =
    a.actions.take(2).any { actionGlyph(it.title) == null }

// ---------------------------------------------------------------------------
// Compact: content hugs both sides of the camera and glides out from under it.
// ---------------------------------------------------------------------------

@Composable
private fun CompactRow(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxSize().padding(start = 7.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.revealFromCenter(-1)) { left() }
        Spacer(Modifier.weight(1f))
        Box(Modifier.revealFromCenter(+1)) { right() }
    }
}

@Composable
internal fun CompactContent(a: LiveActivity) {
    val color = typeColor(a)
    when (a.type) {
        ActivityType.MEDIA -> {
            val media = a.media
            val art = media?.art ?: a.largeIcon
            val accent = rememberAccent(art, IslandColors.Green)
            CompactRow(
                left = { Box(Modifier.padding(start = 2.dp)) { Artwork(art, 24.dp, 7.dp) } },
                right = { Waveform(media?.isPlaying ?: true, accent, Modifier.width(22.dp).height(16.dp)) },
            )
        }
        ActivityType.CALL -> CompactRow(
            left = {
                Row(Modifier.padding(start = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Call, null, tint = color, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    a.chronometerBase?.let { ChronoText(it, false, IslandType.compact, color) }
                }
            },
            right = { Waveform(true, color, Modifier.width(22.dp).height(15.dp)) },
        )
        ActivityType.TIMER -> CompactRow(
            left = { Box(Modifier.padding(start = 4.dp)) { Icon(Icons.Rounded.Timer, null, tint = color, modifier = Modifier.size(20.dp)) } },
            right = { a.chronometerBase?.let { ChronoText(it, a.countDown, IslandType.compact, color) } },
        )
        ActivityType.PROGRESS -> CompactRow(
            left = { Box(Modifier.padding(start = 2.dp)) { AppIcon(a.appIcon, 24.dp, ContinuousRoundedShape(7.dp)) } },
            right = { ProgressRing(a.progress ?: 0f, color, Modifier.size(19.dp), 2.6.dp) },
        )
        ActivityType.NAVIGATION, ActivityType.LIVE -> CompactRow(
            left = {
                if (a.type == ActivityType.NAVIGATION) {
                    Box(Modifier.padding(start = 4.dp)) { Icon(Icons.Rounded.Navigation, null, tint = color, modifier = Modifier.size(20.dp)) }
                } else {
                    Box(Modifier.padding(start = 2.dp)) { AppIcon(a.appIcon, 24.dp, CircleShape) }
                }
            },
            right = {
                val label = a.shortText ?: if (a.chronometerBase == null) a.title else null
                if (label != null) {
                    SingleLine(label, IslandType.compact, Modifier.width(76.dp), color)
                } else {
                    ChronoText(a.chronometerBase!!, a.countDown, IslandType.compact, color)
                }
            },
        )
    }
}

@Composable
internal fun ChargingContent(e: TransientEvent) {
    CompactRow(
        left = { Text("Charging", style = IslandType.compact, color = Color.White, modifier = Modifier.padding(start = 6.dp)) },
        right = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${e.batteryLevel}%", style = IslandType.compact, color = IslandColors.Green)
                Spacer(Modifier.width(6.dp))
                BatteryGlyph(e.batteryLevel, IslandColors.Green, Modifier.width(27.dp).height(13.dp))
            }
        },
    )
}

@Composable
internal fun BubbleContent(a: LiveActivity) {
    when (a.type) {
        ActivityType.MEDIA -> Artwork(a.media?.art ?: a.largeIcon, 24.dp, 12.dp)
        ActivityType.PROGRESS -> ProgressRing(a.progress ?: 0f, typeColor(a), Modifier.size(20.dp), 2.6.dp)
        ActivityType.LIVE -> AppIcon(a.appIcon, 24.dp, CircleShape)
        else -> Icon(typeIcon(a.type), null, tint = typeColor(a), modifier = Modifier.size(18.dp))
    }
}

// ---------------------------------------------------------------------------
// Expanded (long-press).
// ---------------------------------------------------------------------------

@Composable
internal fun ExpandedContent(a: LiveActivity, m: IslandMetrics, cb: IslandCallbacks) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 22.dp, end = 22.dp, top = m.topPad, bottom = 20.dp)
    ) {
        when (a.type) {
            ActivityType.MEDIA -> ExpandedMedia(a)
            ActivityType.CALL -> ExpandedCall(a, cb)
            ActivityType.TIMER -> ExpandedTimer(a, cb)
            else -> ExpandedGeneric(a, cb)
        }
    }
}

@Composable
private fun ExpandedMedia(a: LiveActivity) {
    val media = a.media
    val art = media?.art ?: a.largeIcon
    val accent = rememberAccent(art, IslandColors.Green)
    val playing = media?.isPlaying ?: false
    val controls = media?.controller?.transportControls

    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.revealItem(0)) { Artwork(art, 56.dp, 14.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).revealItem(1)) {
            SingleLine(a.title, IslandType.title, size = 16.sp)
            Spacer(Modifier.height(1.dp))
            SingleLine(a.text, IslandType.subtitle, size = 14.sp)
        }
        Spacer(Modifier.width(10.dp))
        Waveform(playing, accent, Modifier.width(26.dp).height(22.dp).revealItem(1))
    }
    Spacer(Modifier.height(16.dp))

    Box(Modifier.revealItem(2)) {
        if (media != null && media.durationMs > 0) {
            val tick by rememberNow(500)
            val pos = remember(tick, media) { media.positionAt() }
            val fraction by animateFloatAsState(pos.toFloat() / media.durationMs, spring(stiffness = 60f), label = "seek")
            Row(Modifier.fillMaxWidth().height(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(formatDuration(pos), style = IslandType.caption, modifier = Modifier.width(38.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pointerInput(media) {
                            detectTapGestures { offset ->
                                val f = (offset.x / size.width).coerceIn(0f, 1f)
                                controls?.seekTo((f * media.durationMs).toLong())
                            }
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(IslandColors.Track))
                    Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(50)).background(Color.White))
                }
                Text(
                    "-" + formatDuration(media.durationMs - pos), style = IslandType.caption,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.width(42.dp),
                )
            }
        } else {
            Spacer(Modifier.height(16.dp))
        }
    }
    Spacer(Modifier.height(6.dp))

    Row(
        Modifier.fillMaxWidth().height(46.dp).revealItem(3),
        horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphButton(Icons.Rounded.FastRewind, 34.dp) { controls?.skipToPrevious() }
        GlyphButton(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, 44.dp) {
            if (controls != null) {
                if (playing) controls.pause() else controls.play()
            } else if (media != null && a.key.startsWith("demo:")) {
                // Demo activities have no session: just toggle locally.
                val t = SystemClock.elapsedRealtime()
                IslandRepository.upsert(
                    a.copy(media = media.copy(isPlaying = !playing, positionMs = media.positionAt(t), positionUpdatedAt = t))
                )
            }
        }
        GlyphButton(Icons.Rounded.FastForward, 34.dp) { controls?.skipToNext() }
    }
}

/** A bare glyph that shrinks while pressed, like media controls on iOS. */
@Composable
private fun GlyphButton(icon: ImageVector, size: Dp, tint: Color = Color.White, onClick: () -> Unit) {
    Box(Modifier.size(size + 14.dp).pressable(onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size))
    }
}

/** A filled circular button. */
@Composable
private fun CircleButton(icon: ImageVector, background: Color, tint: Color, size: Dp = 48.dp, description: String? = null, onClick: () -> Unit) {
    Box(
        Modifier.size(size).pressable(onClick).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun ExpandedCall(a: LiveActivity, cb: IslandCallbacks) {
    val ringing = a.actions.any { it.kind == ActionKind.ACCEPT }
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.revealItem(0)) {
            val avatar = rememberImage(a.largeIcon)
            if (avatar != null) {
                androidx.compose.foundation.Image(avatar, null, Modifier.size(50.dp).clip(CircleShape))
            } else {
                AppIcon(a.appIcon, 50.dp, CircleShape)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).revealItem(1)) {
            if (ringing) SingleLine(a.text.ifBlank { "Incoming call" }, IslandType.subtitle)
            SingleLine(a.title, IslandType.title, size = 18.sp)
            if (!ringing) {
                a.chronometerBase?.let { ChronoText(it, false, IslandType.subtitle, IslandColors.Green) }
                    ?: SingleLine(a.text, IslandType.subtitle)
            }
        }
        a.actions.filter { it.kind != ActionKind.NEUTRAL }.forEachIndexed { i, action ->
            Spacer(Modifier.width(12.dp))
            val accept = action.kind == ActionKind.ACCEPT
            Box(Modifier.revealItem(2 + i)) {
                CircleButton(
                    if (accept) Icons.Rounded.Call else Icons.Rounded.CallEnd,
                    if (accept) IslandColors.Green else IslandColors.Red, Color.White, 50.dp, action.title,
                ) { cb.open(action.intent, null) }
            }
        }
    }
}

@Composable
private fun ExpandedTimer(a: LiveActivity, cb: IslandCallbacks) {
    val color = IslandColors.Orange
    val actions = a.actions.take(2)
    val glyphs = actions.map { actionGlyph(it.title) }
    val buttons = glyphs.isNotEmpty() && glyphs.all { it != null }
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        if (buttons) {
            actions.forEachIndexed { i, action ->
                val glyph = glyphs[i]!!
                val destructive = glyph == Icons.Rounded.Close
                Box(Modifier.revealItem(i)) {
                    CircleButton(
                        glyph,
                        if (destructive) Color(0xFF3A3A3C) else color.copy(alpha = 0.24f),
                        if (destructive) Color.White else color, 50.dp, action.title,
                    ) { cb.open(action.intent, null) }
                }
                Spacer(Modifier.width(10.dp))
            }
        } else {
            Box(Modifier.revealItem(0)) { GlyphCircle(Icons.Rounded.Timer, color, 50.dp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.revealItem(1)) {
                SingleLine(a.title, IslandType.title)
                if (a.text.isNotBlank()) SingleLine(a.text, IslandType.subtitle)
            }
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.revealItem(2)) {
            if (buttons) SingleLine(a.text.ifBlank { a.title }, IslandType.subtitle, color = color, size = 13.sp)
            a.chronometerBase?.let { ChronoText(it, a.countDown, IslandType.big, color) }
        }
    }
    if (timerNeedsChips(a)) ActionChips(actions, cb, startIndex = 3)
}

@Composable
private fun ActionChips(actions: List<IslandAction>, cb: IslandCallbacks, startIndex: Int) {
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth().height(42.dp).revealItem(startIndex), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        actions.forEach { action ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pressable({ cb.open(action.intent, null) }, pressedScale = 0.94f)
                    .clip(ContinuousRoundedShape(21.dp))
                    .background(IslandColors.Chip),
                contentAlignment = Alignment.Center,
            ) {
                Text(action.title, style = IslandType.button, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ExpandedGeneric(a: LiveActivity, cb: IslandCallbacks) {
    val color = typeColor(a)
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.revealItem(0)) {
            if (a.type == ActivityType.PROGRESS || a.type == ActivityType.LIVE) {
                AppIcon(a.appIcon, 48.dp, ContinuousRoundedShape(12.dp))
            } else {
                GlyphCircle(typeIcon(a.type), color, 48.dp)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).revealItem(1)) {
            SingleLine(a.title, IslandType.title, size = 16.sp)
            if (a.text.isNotBlank()) {
                Text(a.text, style = IslandType.subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.revealItem(2), contentAlignment = Alignment.Center) {
            when {
                a.type == ActivityType.PROGRESS && a.progress != null -> {
                    ProgressRing(a.progress, color, Modifier.size(42.dp), 4.dp)
                    Text("${(a.progress * 100).toInt()}%", style = IslandType.caption, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                a.chronometerBase != null -> ChronoText(a.chronometerBase, a.countDown, IslandType.big, color)
            }
        }
    }
    if (a.actions.isNotEmpty()) ActionChips(a.actions, cb, startIndex = 3)
}

// ---------------------------------------------------------------------------
// Pop-up alert for a fresh notification.
// ---------------------------------------------------------------------------

@Composable
internal fun AlertContent(e: TransientEvent, m: IslandMetrics) {
    Row(
        Modifier
            .fillMaxSize()
            .padding(start = 20.dp, end = 22.dp, top = m.topPad, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val large = rememberImage(e.largeIcon)
        Box(Modifier.revealItem(0)) {
            if (large != null) {
                androidx.compose.foundation.Image(large, null, Modifier.size(46.dp).clip(CircleShape))
            } else {
                AppIcon(e.appIcon, 46.dp, ContinuousRoundedShape(12.dp))
            }
            if (large != null && e.appIcon != null) {
                Box(Modifier.align(Alignment.BottomEnd).padding(0.dp)) {
                    Box(Modifier.size(20.dp).clip(ContinuousRoundedShape(6.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                        AppIcon(e.appIcon, 17.dp, ContinuousRoundedShape(5.dp))
                    }
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).revealItem(1)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SingleLine(e.title, IslandType.title, Modifier.weight(1f))
                Text("now", style = IslandType.caption, color = IslandColors.Gray2)
            }
            if (e.text.isNotBlank()) {
                Text(e.text, style = IslandType.subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
