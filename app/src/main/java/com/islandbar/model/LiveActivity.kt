package com.islandbar.model

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.session.MediaController
import android.os.SystemClock

/** Kinds of ongoing "live activities". Lower priority value wins the main pill. */
enum class ActivityType(val priority: Int) {
    CALL(0),
    NAVIGATION(1),
    TIMER(2),
    MEDIA(3),
    PROGRESS(4),
    LIVE(5),
}

enum class ActionKind { NEUTRAL, ACCEPT, DECLINE }

data class IslandAction(
    val title: String,
    val intent: PendingIntent?,
    val kind: ActionKind = ActionKind.NEUTRAL,
)

data class MediaInfo(
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val isPlaying: Boolean,
    val durationMs: Long,
    val positionMs: Long,
    /** [SystemClock.elapsedRealtime] at which [positionMs] was sampled. */
    val positionUpdatedAt: Long = SystemClock.elapsedRealtime(),
    val speed: Float = 1f,
    val controller: MediaController? = null,
) {
    fun positionAt(now: Long = SystemClock.elapsedRealtime()): Long {
        val p = if (isPlaying) positionMs + ((now - positionUpdatedAt) * speed).toLong() else positionMs
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }
}

data class LiveActivity(
    val key: String,
    val type: ActivityType,
    val packageName: String,
    val title: String,
    val text: String = "",
    val shortText: String? = null,
    val appIcon: Drawable? = null,
    val largeIcon: Bitmap? = null,
    val accentColor: Int = 0,
    /** Wall-clock base for a chronometer (count up from / count down to). */
    val chronometerBase: Long? = null,
    val countDown: Boolean = false,
    /** 0..1, or null when there is no determinate progress. */
    val progress: Float? = null,
    val actions: List<IslandAction> = emptyList(),
    val contentIntent: PendingIntent? = null,
    val media: MediaInfo? = null,
    val postTime: Long = System.currentTimeMillis(),
)

enum class TransientKind { ALERT, CHARGING }

/** Short-lived events that briefly take over the island. */
data class TransientEvent(
    val kind: TransientKind,
    val id: Long = System.nanoTime(),
    val title: String = "",
    val text: String = "",
    val packageName: String = "",
    val appIcon: Drawable? = null,
    val largeIcon: Bitmap? = null,
    val contentIntent: PendingIntent? = null,
    val batteryLevel: Int = 0,
    val durationMs: Long = 4000,
)
