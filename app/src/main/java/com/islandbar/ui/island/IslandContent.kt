package com.islandbar.ui.island

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
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
import com.islandbar.model.LiveActivity
import com.islandbar.model.TransientEvent

private fun typeIcon(type: ActivityType): ImageVector = when (type) {
    ActivityType.CALL -> Icons.Rounded.Call
    ActivityType.NAVIGATION -> Icons.Rounded.Navigation
    ActivityType.TIMER -> Icons.Rounded.Timer
    ActivityType.MEDIA -> Icons.Rounded.PlayArrow
    ActivityType.PROGRESS -> Icons.Rounded.Download
    ActivityType.LIVE -> Icons.Rounded.Timer
}

private fun typeColor(a: LiveActivity): Color = when (a.type) {
    ActivityType.CALL -> IslandColors.Green
    ActivityType.NAVIGATION -> IslandColors.Blue
    ActivityType.TIMER -> IslandColors.Orange
    ActivityType.MEDIA -> Color.White
    ActivityType.PROGRESS -> IslandColors.Blue
    ActivityType.LIVE -> if (a.accentColor != 0) Color(a.accentColor).copy(alpha = 1f) else IslandColors.Orange
}

// ---------------------------------------------------------------------------
// Compact: content hugs both sides of the camera.
// ---------------------------------------------------------------------------

@Composable
private fun CompactRow(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        left()
        Spacer(Modifier.weight(1f))
        right()
    }
}

@Composable
internal fun CompactContent(a: LiveActivity) {
    val color = typeColor(a)
    when (a.type) {
        ActivityType.MEDIA -> {
            val media = a.media
            val accent = rememberAccent(media?.art ?: a.largeIcon, IslandColors.Green)
            CompactRow(
                left = { Artwork(media?.art ?: a.largeIcon, 24.dp, RoundedCornerShape(6.dp)) },
                right = { Equalizer(media?.isPlaying ?: true, accent, Modifier.width(20.dp).height(16.dp)) },
            )
        }
        ActivityType.CALL -> CompactRow(
            left = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Call, null, tint = color, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    a.chronometerBase?.let { ChronoText(it, false, color, 13.sp) }
                }
            },
            right = { Equalizer(true, color, Modifier.width(20.dp).height(14.dp)) },
        )
        ActivityType.TIMER -> CompactRow(
            left = { Icon(Icons.Rounded.Timer, null, tint = color, modifier = Modifier.size(20.dp)) },
            right = { a.chronometerBase?.let { ChronoText(it, a.countDown, color, 14.sp) } },
        )
        ActivityType.PROGRESS -> CompactRow(
            left = { AppIcon(a.appIcon, 22.dp, CircleShape) },
            right = { ProgressRing(a.progress ?: 0f, color, Modifier.size(20.dp)) },
        )
        ActivityType.NAVIGATION, ActivityType.LIVE -> CompactRow(
            left = {
                if (a.type == ActivityType.NAVIGATION) {
                    Icon(Icons.Rounded.Navigation, null, tint = color, modifier = Modifier.size(20.dp))
                } else {
                    AppIcon(a.appIcon, 22.dp, CircleShape)
                }
            },
            right = {
                val label = a.shortText ?: if (a.chronometerBase == null) a.title else null
                if (label != null) {
                    Text(
                        label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(72.dp),
                    )
                } else {
                    ChronoText(a.chronometerBase!!, a.countDown, color, 13.sp)
                }
            },
        )
    }
}

@Composable
internal fun ChargingContent(e: TransientEvent) {
    CompactRow(
        left = {
            Text("Charging", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        },
        right = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${e.batteryLevel}%", color = IslandColors.Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, style = Tabular)
                Icon(Icons.Rounded.BatteryChargingFull, null, tint = IslandColors.Green, modifier = Modifier.size(18.dp))
            }
        },
    )
}

@Composable
internal fun BubbleContent(a: LiveActivity) {
    when (a.type) {
        ActivityType.MEDIA -> Artwork(a.media?.art ?: a.largeIcon, 22.dp, CircleShape)
        ActivityType.PROGRESS -> ProgressRing(a.progress ?: 0f, typeColor(a), Modifier.size(20.dp))
        ActivityType.LIVE -> AppIcon(a.appIcon, 22.dp, CircleShape)
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
            .padding(start = 20.dp, end = 20.dp, top = m.topPad, bottom = 18.dp)
    ) {
        when (a.type) {
            ActivityType.MEDIA -> ExpandedMedia(a)
            ActivityType.CALL -> ExpandedCall(a, cb)
            else -> ExpandedGeneric(a, cb)
        }
    }
}

@Composable
private fun ExpandedMedia(a: LiveActivity) {
    val media = a.media
    val accent = rememberAccent(media?.art ?: a.largeIcon, IslandColors.Green)
    val playing = media?.isPlaying ?: false
    val controls = media?.controller?.transportControls

    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(media?.art ?: a.largeIcon, 56.dp, RoundedCornerShape(12.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(a.text, color = IslandColors.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Equalizer(playing, accent, Modifier.width(24.dp).height(20.dp))
    }
    Spacer(Modifier.height(14.dp))

    if (media != null && media.durationMs > 0) {
        val tick by rememberNow(500)
        val pos = remember(tick, media) { media.positionAt() }
        val fraction = pos.toFloat() / media.durationMs
        Row(Modifier.fillMaxWidth().height(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(formatDuration(pos), color = IslandColors.Gray, fontSize = 11.sp, style = Tabular)
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(16.dp)
                    .pointerInput(media) {
                        detectTapGestures { offset ->
                            val f = (offset.x / size.width).coerceIn(0f, 1f)
                            controls?.seekTo((f * media.durationMs).toLong())
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)).background(IslandColors.Track))
                Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(5.dp).clip(RoundedCornerShape(50)).background(Color.White))
            }
            Spacer(Modifier.width(8.dp))
            Text("-" + formatDuration(media.durationMs - pos), color = IslandColors.Gray, fontSize = 11.sp, style = Tabular)
        }
    } else {
        Spacer(Modifier.height(16.dp))
    }
    Spacer(Modifier.height(8.dp))

    Row(
        Modifier.fillMaxWidth().height(56.dp),
        horizontalArrangement = Arrangement.spacedBy(36.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton(Icons.Rounded.SkipPrevious, 34.dp) { controls?.skipToPrevious() }
        RoundIconButton(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, 44.dp) {
            if (controls != null) {
                if (playing) controls.pause() else controls.play()
            } else if (media != null) {
                // Demo activities have no session: just toggle locally.
                val t = SystemClock.elapsedRealtime()
                IslandRepository.upsert(
                    a.copy(media = media.copy(isPlaying = !playing, positionMs = media.positionAt(t), positionUpdatedAt = t))
                )
            }
        }
        RoundIconButton(Icons.Rounded.SkipNext, 34.dp) { controls?.skipToNext() }
    }
}

@Composable
private fun RoundIconButton(icon: ImageVector, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size + 12.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size))
    }
}

@Composable
private fun ExpandedCall(a: LiveActivity, cb: IslandCallbacks) {
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        val avatar = rememberImage(a.largeIcon)
        if (avatar != null) {
            androidx.compose.foundation.Image(avatar, null, Modifier.size(48.dp).clip(CircleShape))
        } else {
            AppIcon(a.appIcon, 48.dp, CircleShape)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val ringing = a.actions.any { it.kind == ActionKind.ACCEPT }
            if (!ringing && a.chronometerBase != null) {
                ChronoText(a.chronometerBase, false, IslandColors.Green, 13.sp, FontWeight.Normal)
            } else {
                Text(a.text, color = IslandColors.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        a.actions.filter { it.kind != ActionKind.NEUTRAL }.forEach { action ->
            Spacer(Modifier.width(12.dp))
            val accept = action.kind == ActionKind.ACCEPT
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (accept) IslandColors.Green else IslandColors.Red)
                    .clickable { cb.open(action.intent, null) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (accept) Icons.Rounded.Call else Icons.Rounded.CallEnd, action.title, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun ExpandedGeneric(a: LiveActivity, cb: IslandCallbacks) {
    val color = typeColor(a)
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        if (a.type == ActivityType.PROGRESS || a.type == ActivityType.LIVE) {
            AppIcon(a.appIcon, 44.dp, RoundedCornerShape(12.dp))
        } else {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(color.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(typeIcon(a.type), null, tint = color, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (a.text.isNotBlank()) {
                Text(a.text, color = IslandColors.Gray, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        when {
            a.type == ActivityType.PROGRESS && a.progress != null -> Box(contentAlignment = Alignment.Center) {
                ProgressRing(a.progress, color, Modifier.size(40.dp), 4.dp)
                Text("${(a.progress * 100).toInt()}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            a.chronometerBase != null -> ChronoText(a.chronometerBase, a.countDown, color, 30.sp, FontWeight.Medium)
        }
    }
    if (a.actions.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            a.actions.forEach { action ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(IslandColors.Chip)
                        .clickable { cb.open(action.intent, null) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(action.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Pop-up alert for a fresh notification.
// ---------------------------------------------------------------------------

@Composable
internal fun AlertContent(e: TransientEvent, m: IslandMetrics) {
    Row(
        Modifier
            .fillMaxSize()
            .padding(start = 20.dp, end = 20.dp, top = m.topPad, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val large = rememberImage(e.largeIcon)
        if (large != null) {
            androidx.compose.foundation.Image(large, null, Modifier.size(46.dp).clip(CircleShape))
        } else {
            AppIcon(e.appIcon, 46.dp, RoundedCornerShape(12.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (e.text.isNotBlank()) {
                Text(e.text, color = IslandColors.Gray, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (large != null && e.appIcon != null) {
            Spacer(Modifier.width(8.dp))
            AppIcon(e.appIcon, 22.dp, RoundedCornerShape(6.dp))
        }
    }
}
