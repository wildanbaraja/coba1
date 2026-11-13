package com.islandbar.overlay

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.islandbar.data.IslandRepository
import com.islandbar.data.IslandSettings
import com.islandbar.ui.island.Island
import com.islandbar.ui.island.IslandCallbacks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Where the camera hole is, in physical pixels of the current rotation. */
data class IslandGeometry(
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val cutoutCenterXPx: Int,
    val cutoutCenterYPx: Int,
    val cutoutSizePx: Int,
    val hasCutout: Boolean,
    val isLandscape: Boolean,
)

/**
 * A TYPE_ACCESSIBILITY_OVERLAY window pinned over the punch-hole camera.
 *
 * The window is only as large as the island (plus a little room for the spring
 * overshoot) so touches everywhere else go straight to the app underneath.
 * When the island grows, the window grows first; when it shrinks, the window
 * shrinks after the animation settles.
 */
class IslandWindow(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owner = OverlayLifecycleOwner()
    private val outsideTouches = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    private val geometry = MutableStateFlow(computeGeometry())
    private var root: FrameLayout? = null

    private val params = WindowManager.LayoutParams(
        1, 1,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        title = "IslandBar"
    }

    fun attach() {
        if (root != null) return
        owner.onCreate()

        val callbacks = IslandCallbacks(
            onWindowChange = ::applyWindowSize,
            open = ::open,
        )
        val compose = ComposeView(context).apply {
            setContent {
                val geo by geometry.collectAsState()
                val config by IslandSettings.config.collectAsState()
                val activities by IslandRepository.activities.collectAsState()
                val transient by IslandRepository.transient.collectAsState()
                Island(geo, config, activities, transient, outsideTouches, callbacks)
            }
        }
        val frame = object : FrameLayout(context) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    outsideTouches.tryEmit(Unit)
                    return false
                }
                return super.dispatchTouchEvent(ev)
            }
        }
        frame.addView(compose, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.setViewTreeLifecycleOwner(owner)
        frame.setViewTreeSavedStateRegistryOwner(owner)

        computePosition()
        wm.addView(frame, params)
        root = frame
        owner.onResume()

        scope.launch {
            combine(IslandSettings.config, geometry) { _, _ -> }.collect {
                computePosition()
                relayout()
            }
        }
    }

    fun onConfigurationChanged() {
        geometry.value = computeGeometry()
    }

    fun detach() {
        scope.cancel()
        owner.onDestroy()
        root?.let { runCatching { wm.removeViewImmediate(it) } }
        root = null
    }

    private fun computePosition() {
        val geo = geometry.value
        val cfg = IslandSettings.config.value
        val d = context.resources.displayMetrics.density
        val pillHeight = cfg.heightDp * d
        params.x = (geo.cutoutCenterXPx - geo.screenWidthPx / 2f + cfg.offsetXDp * d).roundToInt()
        params.y = (geo.cutoutCenterYPx - pillHeight / 2f + cfg.offsetYDp * d).roundToInt()
    }

    private fun applyWindowSize(width: Int, height: Int, touchable: Boolean) {
        // Even widths keep the centred pill on a whole pixel.
        val w = (width + 1) and 1.inv()
        val flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (w == params.width && height == params.height && flags == params.flags) return
        params.width = w
        params.height = height
        params.flags = flags
        relayout()
    }

    private fun relayout() {
        val v = root ?: return
        runCatching { wm.updateViewLayout(v, params) }
    }

    private fun computeGeometry(): IslandGeometry {
        val metrics = wm.currentWindowMetrics
        val b = metrics.bounds
        val landscape = b.width() > b.height()
        val rect = metrics.windowInsets.displayCutout?.boundingRectTop
        return if (rect != null && !rect.isEmpty) {
            IslandGeometry(
                b.width(), b.height(), rect.centerX(), rect.centerY(),
                minOf(rect.width(), rect.height()), hasCutout = true, isLandscape = landscape,
            )
        } else {
            val statusBar = metrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
            IslandGeometry(
                b.width(), b.height(), b.width() / 2, statusBar / 2,
                statusBar / 2, hasCutout = false, isLandscape = landscape,
            )
        }
    }

    /** Launches a notification's PendingIntent (or the app) from the overlay. */
    private fun open(intent: PendingIntent?, packageName: String?) {
        try {
            if (intent != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val opts = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        )
                    intent.send(context, 0, null, null, null, null, opts.toBundle())
                } else {
                    intent.send()
                }
            } else if (packageName != null) {
                context.packageManager.getLaunchIntentForPackage(packageName)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                }
            }
        } catch (t: Throwable) {
            Log.w("IslandWindow", "Unable to open", t)
        }
    }
}
