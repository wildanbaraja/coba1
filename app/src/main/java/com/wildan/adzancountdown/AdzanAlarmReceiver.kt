package com.wildan.adzancountdown

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AdzanAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ADZAN) return
        val prayer = intent.getStringExtra(EXTRA_PRAYER)
            ?.let { runCatching { Prayer.valueOf(it) }.getOrNull() }
            ?: return
        val minutes = Settings(context).countdownMinutes
        CountdownService.start(context, prayer, System.currentTimeMillis() + minutes * 60_000L, minutes)
        AdzanScheduler.scheduleNext(context)
    }

    companion object {
        const val ACTION_ADZAN = "com.wildan.adzancountdown.ACTION_ADZAN"
        const val EXTRA_PRAYER = "prayer"
    }
}
