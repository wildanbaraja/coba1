package com.islandbar.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.session.MediaSession
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import com.islandbar.data.IslandRepository
import com.islandbar.data.IslandSettings
import com.islandbar.model.ActionKind
import com.islandbar.model.ActivityType
import com.islandbar.model.IslandAction
import com.islandbar.model.LiveActivity
import com.islandbar.model.TransientEvent
import com.islandbar.model.TransientKind

/**
 * Turns notifications into island content:
 *  - ongoing calls, media sessions, timers/stopwatches, navigation, progress and
 *    Android 16 "Live Updates" become persistent live activities;
 *  - new, interruptive notifications become short pop-up alerts.
 */
class IslandNotificationListener : NotificationListenerService() {

    private val trackers = HashMap<String, MediaTracker>()
    private val alerted = HashSet<String>()
    private val iconCache = HashMap<String, Drawable?>()

    override fun onListenerConnected() {
        IslandSettings.init(this)
        IslandRepository.listenerConnected.value = true
        runCatching { activeNotifications }.getOrNull()?.forEach { handle(it, initial = true) }
    }

    override fun onListenerDisconnected() {
        IslandRepository.listenerConnected.value = false
        trackers.values.forEach { it.release() }
        trackers.clear()
        IslandRepository.removeIf { !it.key.startsWith(DEMO_PREFIX) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn, initial = false)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to handle ${sbn.key}", t)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        trackers.remove(sbn.key)?.release()
        alerted.remove(sbn.key)
        IslandRepository.remove(sbn.key)
    }

    private fun handle(sbn: StatusBarNotification, initial: Boolean) {
        if (sbn.packageName == packageName || sbn.packageName in IGNORED_PACKAGES) return
        val n = sbn.notification
        val ex = n.extras
        val title = (ex.getCharSequence(Notification.EXTRA_TITLE)
            ?: ex.getCharSequence(Notification.EXTRA_TITLE_BIG))?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString().orEmpty()
        val token = ex.parcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
        val isCallStyle = ex.getString(Notification.EXTRA_TEMPLATE) == "android.app.Notification\$CallStyle"
        val progressMax = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val indeterminate = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val promoted = ex.getBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, false)
        val usesChrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        // One UI 7+ "Live notifications" (the ones shown in the lock-screen Now Bar)
        // carry Samsung-specific extras; their presence marks a live activity.
        val samsungLive = ex.keySet().any { it.startsWith(SAMSUNG_LIVE_PREFIX) }
        val samsungTitle = ex.samsungText("primaryInfo")
        val samsungText = ex.samsungText("secondaryInfo")
        val samsungChip = ex.samsungText("chipExpandedText") ?: ex.samsungText("nowbarPrimaryInfo")
        val samsungProgressMax = ex.getInt(SAMSUNG_LIVE_PREFIX + "progressMax", 0)
        val isSamsungClock = sbn.packageName == SAMSUNG_CLOCK

        val type = when {
            (n.category == Notification.CATEGORY_CALL || isCallStyle) &&
                (sbn.isOngoing || n.fullScreenIntent != null) -> ActivityType.CALL
            token != null -> ActivityType.MEDIA
            n.category == Notification.CATEGORY_NAVIGATION && sbn.isOngoing -> ActivityType.NAVIGATION
            usesChrono && sbn.isOngoing -> ActivityType.TIMER
            isSamsungClock && sbn.isOngoing && (samsungLive || promoted) -> ActivityType.TIMER
            sbn.isOngoing && progressMax > 0 && !indeterminate -> ActivityType.PROGRESS
            samsungLive && samsungProgressMax > 0 -> ActivityType.PROGRESS
            promoted || (samsungLive && sbn.isOngoing) -> ActivityType.LIVE
            else -> null
        }

        if (type == null) {
            trackers.remove(sbn.key)?.release()
            IslandRepository.remove(sbn.key)
            if (!initial) maybeAlert(sbn, title, text)
            return
        }

        val activity = LiveActivity(
            key = sbn.key,
            type = type,
            packageName = sbn.packageName,
            title = (samsungTitle ?: title).ifBlank { appLabel(sbn.packageName) },
            text = samsungText ?: text,
            shortText = ex.getCharSequence(EXTRA_SHORT_CRITICAL_TEXT)?.toString() ?: samsungChip
                ?: if (type == ActivityType.TIMER && !usesChrono) samsungTitle ?: title.takeIf { it.isNotBlank() } else null,
            appIcon = appIcon(sbn),
            largeIcon = largeIcon(n),
            accentColor = n.color,
            chronometerBase = if (usesChrono || type == ActivityType.CALL) n.`when`.takeIf { it > 0 } else null,
            countDown = ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false),
            progress = when {
                progressMax > 0 && !indeterminate -> ex.getInt(Notification.EXTRA_PROGRESS, 0).toFloat() / progressMax
                samsungProgressMax > 0 -> ex.getInt(SAMSUNG_LIVE_PREFIX + "progress", 0).toFloat() / samsungProgressMax
                else -> null
            },
            actions = if (type == ActivityType.CALL) callActions(n) else genericActions(n),
            contentIntent = n.contentIntent,
            postTime = sbn.postTime,
        )

        if (type == ActivityType.MEDIA && token != null) {
            val existing = trackers[sbn.key]
            if (existing != null && existing.token == token) {
                existing.update(activity)
            } else {
                existing?.release()
                trackers[sbn.key] = MediaTracker(this, token, activity, initial)
            }
        } else {
            trackers.remove(sbn.key)?.release()
            IslandRepository.upsert(activity)
        }
    }

    private fun maybeAlert(sbn: StatusBarNotification, title: String, text: String) {
        if (!IslandSettings.config.value.showAlerts) return
        val n = sbn.notification
        if (sbn.isOngoing || title.isBlank()) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 && sbn.key in alerted) return
        val ranking = Ranking()
        if (currentRanking?.getRanking(sbn.key, ranking) == true) {
            if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return
            if (ranking.isAmbient || !ranking.matchesInterruptionFilter()) return
        }
        alerted += sbn.key
        IslandRepository.showTransient(
            TransientEvent(
                kind = TransientKind.ALERT,
                title = title,
                text = text,
                packageName = sbn.packageName,
                appIcon = appIcon(sbn),
                largeIcon = largeIcon(n),
                contentIntent = n.contentIntent,
            )
        )
    }

    private fun callActions(n: Notification): List<IslandAction> {
        val ex = n.extras
        val result = mutableListOf<IslandAction>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ex.parcelable<PendingIntent>(Notification.EXTRA_DECLINE_INTENT)
                ?.let { result += IslandAction("Decline", it, ActionKind.DECLINE) }
            ex.parcelable<PendingIntent>(Notification.EXTRA_HANG_UP_INTENT)
                ?.let { result += IslandAction("End", it, ActionKind.DECLINE) }
            ex.parcelable<PendingIntent>(Notification.EXTRA_ANSWER_INTENT)
                ?.let { result += IslandAction("Answer", it, ActionKind.ACCEPT) }
        }
        if (result.isNotEmpty()) return result
        return n.actions.orEmpty().mapNotNull { a ->
            val t = a.title?.toString() ?: return@mapNotNull null
            val lower = t.lowercase()
            val kind = when {
                ACCEPT_WORDS.any { it in lower } -> ActionKind.ACCEPT
                DECLINE_WORDS.any { it in lower } -> ActionKind.DECLINE
                else -> return@mapNotNull null
            }
            IslandAction(t, a.actionIntent, kind)
        }.sortedBy { it.kind != ActionKind.DECLINE }
    }

    private fun genericActions(n: Notification): List<IslandAction> =
        n.actions.orEmpty()
            .filter { it.remoteInputs.isNullOrEmpty() && !it.title.isNullOrBlank() }
            .take(3)
            .map { IslandAction(it.title.toString(), it.actionIntent) }

    private fun appIcon(sbn: StatusBarNotification): Drawable? = iconCache.getOrPut(sbn.packageName) {
        runCatching { packageManager.getApplicationIcon(sbn.packageName) }.getOrNull()
            ?: runCatching { sbn.notification.smallIcon?.loadDrawable(this) }.getOrNull()
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun largeIcon(n: Notification): Bitmap? = runCatching {
        n.getLargeIcon()?.loadDrawable(this)?.toBitmap(192, 192)
    }.getOrNull()

    companion object {
        private const val TAG = "IslandListener"
        const val DEMO_PREFIX = "demo:"

        // Android 16 (API 36) extras, referenced by value so we can compile against API 35.
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
        private const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"

        // Samsung One UI live-notification extras (best effort: undocumented keys).
        private const val SAMSUNG_LIVE_PREFIX = "android.ongoingActivityNoti."
        private const val SAMSUNG_CLOCK = "com.sec.android.app.clockpackage"

        private fun Bundle.samsungText(name: String): String? =
            getCharSequence(SAMSUNG_LIVE_PREFIX + name)?.toString()?.takeIf { it.isNotBlank() }

        private val IGNORED_PACKAGES = setOf("android", "com.android.systemui")
        private val ACCEPT_WORDS = listOf("answer", "accept", "jawab", "terima")
        private val DECLINE_WORDS = listOf("decline", "reject", "hang up", "end", "tolak", "akhiri", "tutup")
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Bundle.parcelable(key: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelable(key, T::class.java)
    else getParcelable(key) as? T
