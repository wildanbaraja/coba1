package com.wildan.adzancountdown

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date

/**
 * Builds the countdown notification.
 *
 * On Android 16+ (Samsung One UI 8 / One UI 9) the notification is requested as a
 * *Live Update* ("promoted ongoing" notification). When the system accepts it, it is
 * shown as a chip in the status bar with the ticking countdown, on the lock screen /
 * AOD, and in Samsung's Now Bar. Requirements the system checks for promotion:
 *  - ongoing, has a title, not colorized, no custom RemoteViews
 *  - standard / BigText / Call / Progress style
 *  - app holds POST_PROMOTED_NOTIFICATIONS and the user hasn't turned Live Updates off
 *  - channel importance is not MIN
 * On older Android versions the same notification is shown as a normal ongoing
 * notification with a countdown chronometer.
 */
object CountdownNotification {
    const val CHANNEL_COUNTDOWN = "countdown"
    const val CHANNEL_IQAMAH = "iqamah"
    const val NOTIFICATION_ID = 42
    const val IQAMAH_NOTIFICATION_ID = 43

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val countdown = NotificationChannel(
            CHANNEL_COUNTDOWN,
            context.getString(R.string.channel_countdown),
            // DEFAULT (not LOW/MIN) so it is eligible for promotion; the sound is removed instead.
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_countdown_desc)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            // Samsung's Now Bar needs the content to be allowed on the lock screen.
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val iqamah = NotificationChannel(
            CHANNEL_IQAMAH,
            context.getString(R.string.channel_iqamah),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.channel_iqamah_desc) }
        nm.createNotificationChannels(listOf(countdown, iqamah))
    }

    fun build(
        context: Context,
        prayer: Prayer,
        endAtMillis: Long,
        totalMinutes: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): Notification {
        val totalSeconds = totalMinutes * 60
        val remainingMs = (endAtMillis - nowMillis).coerceAtLeast(0)
        val elapsedSeconds = (totalSeconds - (remainingMs / 1000).toInt()).coerceIn(0, totalSeconds)
        val remainingSecondsCeil = (remainingMs + 999) / 1000
        val remainingText = "%d:%02d".format(remainingSecondsCeil / 60, remainingSecondsCeil % 60)
        val timeFmt = DateFormat.getTimeInstance(DateFormat.SHORT)
        val adzanAt = timeFmt.format(Date(endAtMillis - totalSeconds * 1000L))
        val iqamahAt = timeFmt.format(Date(endAtMillis))
        val accent = ContextCompat.getColor(context, R.color.accent)

        val progressStyle = NotificationCompat.ProgressStyle()
            .setProgressSegments(
                listOf(NotificationCompat.ProgressStyle.Segment(totalSeconds).setColor(accent)),
            )
            .setStyledByProgress(true)
            .setProgress(elapsedSeconds)

        return NotificationCompat.Builder(context, CHANNEL_COUNTDOWN)
            .setSmallIcon(R.drawable.ic_stat_mosque)
            .setContentTitle(context.getString(R.string.notif_title, prayer.displayName))
            // Samsung's Now Bar pill shows title + text but not the chronometer, so the remaining
            // time goes first in the text (CountdownService refreshes it every second).
            .setContentText(context.getString(R.string.notif_text, remainingText, iqamahAt))
            .setSubText(context.getString(R.string.notif_subtext, adzanAt))
            .setStyle(progressStyle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // No setSilent(): NotificationCompat implements it by putting the notification in a
            // "silent" group, which OEMs may treat as ineligible. The channel has no sound instead.
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // The countdown itself: rendered by System UI, so it ticks every second
            // in the shade and in the status bar chip without us updating anything.
            .setWhen(endAtMillis)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setTimeoutAfter(remainingMs + 5_000)
            // Ask Android 16+ to promote this to a Live Update (status bar chip / Now Bar).
            .setRequestPromotedOngoing(true)
            .setContentIntent(openAppIntent(context))
            .addAction(0, context.getString(R.string.action_stop), stopIntent(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun showIqamah(context: Context, prayer: Prayer) {
        val n = NotificationCompat.Builder(context, CHANNEL_IQAMAH)
            .setSmallIcon(R.drawable.ic_stat_mosque)
            .setContentTitle(context.getString(R.string.iqamah_title, prayer.displayName))
            .setContentText(context.getString(R.string.iqamah_text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
            .setContentIntent(openAppIntent(context))
            .build()
        notify(context, IQAMAH_NOTIFICATION_ID, n)
    }

    fun notify(context: Context, id: Int, n: Notification) {
        if (!canPost(context)) return
        context.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    fun canPost(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    enum class LiveStatus {
        /** Android 15 or older: Live Updates don't exist, a normal notification is shown. */
        UNSUPPORTED_OS,
        /** The system currently shows our countdown as a Live Update. */
        PROMOTED,
        /** canPostPromotedNotifications() is false: user toggle off, or Samsung's allowlist. */
        BLOCKED_BY_SYSTEM,
        /** Countdown is showing but doesn't meet Android's promotion rules (would be a bug here). */
        NOT_ELIGIBLE,
        /** Allowed and eligible, but the system still didn't promote it (OEM-specific criteria). */
        NOT_PROMOTED,
        /** Allowed; no countdown is showing right now, so promotion can't be verified yet. */
        READY,
    }

    /**
     * What the system actually did with our notification. The FLAG_PROMOTED_ONGOING flag is set
     * by the system (not by us) on the posted notification when it is promoted.
     */
    fun liveStatus(context: Context): LiveStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return LiveStatus.UNSUPPORTED_OS
        val nm = context.getSystemService(NotificationManager::class.java)
        val active = runCatching { nm.activeNotifications }.getOrNull()
            ?.firstOrNull { it.id == NOTIFICATION_ID }?.notification
        return when {
            active != null && active.flags and Notification.FLAG_PROMOTED_ONGOING != 0 -> LiveStatus.PROMOTED
            !nm.canPostPromotedNotifications() -> LiveStatus.BLOCKED_BY_SYSTEM
            active == null -> LiveStatus.READY
            !active.hasPromotableCharacteristics() -> LiveStatus.NOT_ELIGIBLE
            else -> LiveStatus.NOT_PROMOTED
        }
    }

    /** Whether the user allows Live Updates (Android 16+). Always true on older versions. */
    fun canPromote(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA ||
            context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopIntent(context: Context): PendingIntent = PendingIntent.getService(
        context, 1,
        Intent(context, CountdownService::class.java).setAction(CountdownService.ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
