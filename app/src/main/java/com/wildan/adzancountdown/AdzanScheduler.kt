package com.wildan.adzancountdown

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class NextAdzan(val prayer: Prayer, val time: Instant)

/**
 * Keeps exactly one alarm armed: the next enabled adzan. When it fires,
 * [AdzanAlarmReceiver] starts the countdown and calls [scheduleNext] again.
 */
object AdzanScheduler {
    private const val REQUEST_CODE = 1001

    fun findNext(context: Context, after: Instant = Instant.now()): NextAdzan? {
        val settings = Settings(context)
        val today = LocalDate.now(ZoneId.systemDefault())
        // Look a couple of days ahead in case all of today's prayers have passed.
        for (offset in -1L..2L) {
            val times = PrayerSchedule.timesFor(context, today.plusDays(offset)).times
            times.entries
                .filter { settings.isEnabled(it.key) && it.value.isAfter(after) }
                .minByOrNull { it.value }
                ?.let { return NextAdzan(it.key, it.value) }
        }
        return null
    }

    fun canScheduleExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    fun scheduleNext(context: Context): NextAdzan? {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(context, null)
        am.cancel(pi)

        val next = findNext(context) ?: return null
        val operation = pendingIntent(context, next.prayer)
        val trigger = next.time.toEpochMilli()
        if (canScheduleExact(context)) {
            // Exact alarms are also what allows starting the foreground service from the background.
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation)
        }
        return next
    }

    /**
     * For broadcast receivers: download missing schedule months (if any) off the main
     * thread, then re-arm the alarm.
     */
    fun refreshAndScheduleAsync(context: Context, pending: android.content.BroadcastReceiver.PendingResult) {
        val app = context.applicationContext
        Thread {
            try {
                runCatching { PrayerSchedule.refresh(app) }
                scheduleNext(app)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun pendingIntent(context: Context, prayer: Prayer?): PendingIntent {
        val intent = Intent(context, AdzanAlarmReceiver::class.java).apply {
            action = AdzanAlarmReceiver.ACTION_ADZAN
            prayer?.let { putExtra(AdzanAlarmReceiver.EXTRA_PRAYER, it.name) }
        }
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
