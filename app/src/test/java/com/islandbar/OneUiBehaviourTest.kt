package com.islandbar

import android.app.Notification
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.islandbar.data.IslandConfig
import com.islandbar.data.IslandRepository
import com.islandbar.data.SystemUiState
import com.islandbar.model.ActivityType
import com.islandbar.model.LiveActivity
import com.islandbar.model.MediaInfo
import com.islandbar.overlay.IslandGeometry
import com.islandbar.service.IslandNotificationListener
import com.islandbar.ui.island.Island
import com.islandbar.ui.island.IslandCallbacks
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Behaviour specific to the Galaxy S25 Ultra / One UI integration. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-xxhdpi")
class OneUiBehaviourTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val geo = IslandGeometry(1200, 2400, 600, 54, 36, hasCutout = true, isLandscape = false)
    private val music = LiveActivity(
        "music", ActivityType.MEDIA, "p", "Midnight Drive", "Neon Coast",
        media = MediaInfo("Midnight Drive", "Neon Coast", null, true, 214_000, 61_000),
    )

    private var lastTouchable: Boolean? = null
    private var shadeRequests = 0
    private val callbacks = IslandCallbacks(
        onWindowChange = { _, _, t -> lastTouchable = t },
        open = { _, _ -> },
        openNotificationShade = { shadeRequests++ },
    )

    private fun hover(action: Int, xDp: Float, yDp: Float) {
        val d = compose.activity.resources.displayMetrics.density
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = xDp * d; y = yDp * d })
        val t = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(t, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        compose.runOnUiThread { compose.activity.window.decorView.dispatchGenericMotionEvent(ev) }
        ev.recycle()
    }

    @Test
    fun sPenHoverPeeksAndFoldsBack() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.width(400.dp).height(300.dp)) {
                Island(geo, IslandConfig(), listOf(music), null, emptyFlow(), callbacks)
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Midnight Drive").assertDoesNotExist() // compact shows art + waveform only

        hover(MotionEvent.ACTION_HOVER_ENTER, 200f, 18f)
        hover(MotionEvent.ACTION_HOVER_MOVE, 201f, 18f)
        compose.mainClock.advanceTimeBy(1200)
        compose.onNodeWithText("Midnight Drive").assertExists()

        hover(MotionEvent.ACTION_HOVER_EXIT, 200f, 400f)
        compose.mainClock.advanceTimeBy(2000)
        compose.onNodeWithText("Midnight Drive").assertDoesNotExist()
    }

    @Test
    fun hidesWhileOneUiPanelIsOpen() {
        compose.mainClock.autoAdvance = false
        var ui by mutableStateOf(SystemUiState())
        compose.setContent {
            Island(geo, IslandConfig(), emptyList(), null, emptyFlow(), callbacks, systemUi = ui)
        }
        compose.mainClock.advanceTimeBy(1500)
        assertEquals(true, lastTouchable)

        compose.runOnUiThread { ui = SystemUiState(shadeOpen = true); Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(1500)
        assertEquals(false, lastTouchable)

        compose.runOnUiThread { ui = SystemUiState(fullscreen = true); Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(1500)
        assertEquals(false, lastTouchable)

        compose.runOnUiThread { ui = SystemUiState(); Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(1500)
        assertEquals(true, lastTouchable)
    }

    @Test
    fun swipeDownOnIdleIslandOpensNotificationPanel() {
        compose.setContent {
            Box(Modifier.width(400.dp).height(300.dp)) {
                Island(geo, IslandConfig(), emptyList(), null, emptyFlow(), callbacks)
            }
        }
        compose.waitForIdle()
        compose.onRoot().performTouchInput {
            val x = (200.dp).toPx()
            swipe(Offset(x, 18.dp.toPx()), Offset(x, 160.dp.toPx()), durationMillis = 250)
        }
        compose.waitForIdle()
        assertEquals(1, shadeRequests)
    }

    // --- Samsung "Live notifications" (Now Bar) ------------------------------

    private fun post(pkg: String, extras: Bundle, ongoing: Boolean = true, key: Int = 1): LiveActivity? {
        val listener = Robolectric.buildService(IslandNotificationListener::class.java).create().get()
        val n = Notification.Builder(compose.activity, "c")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Fallback title")
            .setOngoing(ongoing)
            .addExtras(extras)
            .build()
        @Suppress("DEPRECATION")
        val sbn = StatusBarNotification(pkg, pkg, key, null, 0, 0, 0, n, Process.myUserHandle(), System.currentTimeMillis())
        listener.onNotificationPosted(sbn)
        return IslandRepository.get(sbn.key)
    }

    @Test
    fun samsungClockLiveNotificationBecomesTimer() {
        val a = post(
            "com.sec.android.app.clockpackage",
            Bundle().apply {
                putCharSequence("android.ongoingActivityNoti.primaryInfo", "04:59")
                putCharSequence("android.ongoingActivityNoti.secondaryInfo", "Timer")
            },
        )
        assertNotNull(a)
        assertEquals(ActivityType.TIMER, a!!.type)
        assertEquals("04:59", a.shortText)
        assertEquals("Timer", a.text)
    }

    @Test
    fun samsungLiveNotificationFromOtherAppsIsLive() {
        val a = post(
            "com.sec.android.app.shealth",
            Bundle().apply {
                putCharSequence("android.ongoingActivityNoti.primaryInfo", "Running")
                putCharSequence("android.ongoingActivityNoti.secondaryInfo", "2.4 km · 14:02")
            },
            key = 2,
        )
        assertEquals(ActivityType.LIVE, a?.type)
        assertEquals("Running", a?.title)
    }

    @Test
    fun samsungLiveProgress() {
        val a = post(
            "com.samsung.android.app.smartswitch",
            Bundle().apply {
                putInt("android.ongoingActivityNoti.progressMax", 100)
                putInt("android.ongoingActivityNoti.progress", 40)
            },
            key = 3,
        )
        assertEquals(ActivityType.PROGRESS, a?.type)
        assertEquals(0.4f, a!!.progress!!, 0.001f)
    }

    @Test
    fun plainOngoingNotificationIsIgnored() {
        assertNull(post("com.example.vpn", Bundle(), key = 4))
        assertFalse(IslandRepository.activities.value.any { it.packageName == "com.example.vpn" })
    }
}
