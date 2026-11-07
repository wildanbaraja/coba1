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
        val remainingMinutes = ((remainingMs + 59_999) / 60_000).toInt()
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
            .setContentText(context.getString(R.string.notif_text, adzanAt, iqamahAt))
            .setSubText(context.getString(R.string.notif_subtext, remainingMinutes))
            .setStyle(progressStyle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
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
