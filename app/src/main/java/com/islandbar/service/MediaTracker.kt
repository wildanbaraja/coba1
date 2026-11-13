package com.islandbar.service

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.islandbar.data.IslandRepository
import com.islandbar.data.IslandSettings
import com.islandbar.model.LiveActivity
import com.islandbar.model.MediaInfo

/**
 * Follows one media session and keeps its [LiveActivity] up to date.
 * Like the iPhone, a paused player lingers briefly and then leaves the island.
 */
class MediaTracker(
    context: Context,
    val token: MediaSession.Token,
    private var base: LiveActivity,
    initial: Boolean,
) {
    private val controller = MediaController(context, token)
    private val handler = Handler(Looper.getMainLooper())
    private var hiddenWhilePaused = initial && !isPlaying(controller.playbackState)

    private val hideRunnable = Runnable {
        if (IslandSettings.config.value.hidePausedMedia) {
            hiddenWhilePaused = true
            IslandRepository.remove(base.key)
        }
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() {
            handler.removeCallbacks(hideRunnable)
            IslandRepository.remove(base.key)
        }
    }

    init {
        controller.registerCallback(callback, handler)
        publish()
    }

    fun update(newBase: LiveActivity) {
        base = newBase
        publish()
    }

    private fun publish() {
        val md = controller.metadata
        val st = controller.playbackState
        val playing = isPlaying(st)

        if (playing) {
            hiddenWhilePaused = false
            handler.removeCallbacks(hideRunnable)
        } else {
            if (hiddenWhilePaused) return
            handler.removeCallbacks(hideRunnable)
            handler.postDelayed(hideRunnable, PAUSE_GRACE_MS)
        }

        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: base.title
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: base.text
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: base.largeIcon
        val updatedAt = st?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()

        val info = MediaInfo(
            title = title,
            artist = artist,
            art = art,
            isPlaying = playing,
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            positionMs = st?.position ?: 0L,
            positionUpdatedAt = updatedAt,
            speed = st?.playbackSpeed?.takeIf { it != 0f } ?: 1f,
            controller = controller,
        )
        IslandRepository.upsert(base.copy(title = title, text = artist, media = info))
    }

    fun release() {
        handler.removeCallbacks(hideRunnable)
        controller.unregisterCallback(callback)
    }

    private companion object {
        const val PAUSE_GRACE_MS = 45_000L

        fun isPlaying(st: PlaybackState?) =
            st?.state == PlaybackState.STATE_PLAYING || st?.state == PlaybackState.STATE_BUFFERING
    }
}
