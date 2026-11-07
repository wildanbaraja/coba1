package com.wildan.adzancountdown

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that lives for the duration of the countdown. The seconds in the
 * chronometer / status bar chip are ticked by System UI; this service only refreshes
 * the progress bar periodically and posts the "iqamah" alert at the end.
 */
class CountdownService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var prayer: Prayer? = null
    private var endAt = 0L
    private var totalMinutes = 15

    private val tick = object : Runnable {
        override fun run() {
            val p = prayer ?: return
            val now = System.currentTimeMillis()
            if (now >= endAt) {
                CountdownNotification.showIqamah(this@CountdownService, p)
                stopCountdown()
                return
            }
            CountdownNotification.notify(
                this@CountdownService,
                CountdownNotification.NOTIFICATION_ID,
                CountdownNotification.build(this@CountdownService, p, endAt, totalMinutes, now),
            )
            handler.postDelayed(this, minOf(UPDATE_INTERVAL_MS, endAt - now))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCountdown()
            return START_NOT_STICKY
        }
        val p = intent?.getStringExtra(EXTRA_PRAYER)?.let { runCatching { Prayer.valueOf(it) }.getOrNull() }
        val end = intent?.getLongExtra(EXTRA_END_AT, 0L) ?: 0L
        if (p == null || end <= System.currentTimeMillis()) {
            stopSelf()
            return START_NOT_STICKY
        }
        prayer = p
        endAt = end
        totalMinutes = intent.getIntExtra(EXTRA_TOTAL_MINUTES, 15)

        CountdownNotification.createChannels(this)
        val notification = CountdownNotification.build(this, p, endAt, totalMinutes)
        ServiceCompat.startForeground(
            this,
            CountdownNotification.NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, minOf(UPDATE_INTERVAL_MS, endAt - System.currentTimeMillis()))
        return START_NOT_STICKY
    }

    private fun stopCountdown() {
        handler.removeCallbacks(tick)
        prayer = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        CountdownNotification.cancel(this)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.wildan.adzancountdown.ACTION_STOP"
        private const val EXTRA_PRAYER = "prayer"
        private const val EXTRA_END_AT = "end_at"
        private const val EXTRA_TOTAL_MINUTES = "total_minutes"
        private const val UPDATE_INTERVAL_MS = 15_000L
        private const val TAG = "CountdownService"

        fun start(context: Context, prayer: Prayer, endAtMillis: Long, totalMinutes: Int) {
            CountdownNotification.createChannels(context)
            val intent = Intent(context, CountdownService::class.java)
                .putExtra(EXTRA_PRAYER, prayer.name)
                .putExtra(EXTRA_END_AT, endAtMillis)
                .putExtra(EXTRA_TOTAL_MINUTES, totalMinutes)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException when the alarm was inexact.
                // Fall back to a static notification: the chronometer still counts down by
                // itself and setTimeoutAfter removes it at the end.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e !is ForegroundServiceStartNotAllowedException
                ) {
                    throw e
                }
                Log.w(TAG, "Could not start foreground service, posting plain notification", e)
                CountdownNotification.notify(
                    context,
                    CountdownNotification.NOTIFICATION_ID,
                    CountdownNotification.build(context, prayer, endAtMillis, totalMinutes),
                )
            }
        }
    }
}
