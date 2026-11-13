package com.islandbar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.islandbar.data.IslandConfig
import com.islandbar.model.ActionKind
import com.islandbar.model.ActivityType
import com.islandbar.model.IslandAction
import com.islandbar.model.LiveActivity
import com.islandbar.model.MediaInfo
import com.islandbar.model.TransientEvent
import com.islandbar.model.TransientKind
import com.islandbar.overlay.IslandGeometry
import com.islandbar.ui.island.Island
import com.islandbar.ui.island.IslandCallbacks
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders every island state into build/island-states.png so the visuals can be
 * checked without a device. Run with: ./gradlew testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h1600dp-xxhdpi")
class IslandScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // S25 Ultra at FHD+ scaled to xxhdpi: centred hole ~30px wide, ~40px from the top.
    private val geo = IslandGeometry(1200, 2600, 600, 42, 36, hasCutout = true, isLandscape = false)
    private val noop = IslandCallbacks({ _, _, _ -> }, { _, _ -> })

    private val art: Bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).also {
        val p = Paint().apply {
            shader = LinearGradient(0f, 0f, 120f, 120f, 0xFFFF2D55.toInt(), 0xFFFF9F0A.toInt(), Shader.TileMode.CLAMP)
        }
        Canvas(it).drawRect(0f, 0f, 120f, 120f, p)
    }
    private val icon = GradientDrawable().apply { setColor(0xFF34C759.toInt()); setSize(96, 96) }

    private val now = System.currentTimeMillis()
    private val music = LiveActivity(
        "music", ActivityType.MEDIA, "p", "Midnight Drive", "Neon Coast",
        media = MediaInfo("Midnight Drive", "Neon Coast", art, true, 214_000, 61_000),
    )
    private val timer = LiveActivity(
        "timer", ActivityType.TIMER, "p", "Timer", "Pasta", chronometerBase = now + 272_000, countDown = true,
        actions = listOf(IslandAction("Pause", null), IslandAction("Cancel", null)),
    )
    private val call = LiveActivity(
        "call", ActivityType.CALL, "p", "Alex Morgan", "mobile", appIcon = icon, chronometerBase = now,
        actions = listOf(IslandAction("Decline", null, ActionKind.DECLINE), IslandAction("Answer", null, ActionKind.ACCEPT)),
    )
    private val download = LiveActivity("dl", ActivityType.PROGRESS, "p", "Downloading", "photos.zip", appIcon = icon, progress = 0.62f)

    @Composable
    private fun Frame(label: String, height: Int, content: @Composable () -> Unit) {
        Column {
            Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp, top = 6.dp))
            Box(
                Modifier
                    .width(400.dp)
                    .height(height.dp)
                    .background(Brush.verticalGradient(listOf(Color(0xFF3A5BA0), Color(0xFF8E5BB5))))
            ) { content() }
        }
    }

    @Test
    fun renderAllStates() {
        compose.mainClock.autoAdvance = false
        val cfg = IslandConfig()
        compose.setContent {
            Column(
                Modifier.fillMaxWidth().background(Color(0xFF111111)),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Frame("Idle", 60) { Island(geo, cfg, emptyList(), null, emptyFlow(), noop) }
                Frame("Compact – music", 60) { Island(geo, cfg, listOf(music), null, emptyFlow(), noop) }
                Frame("Compact – timer + music bubble", 60) { Island(geo, cfg, listOf(timer, music), null, emptyFlow(), noop) }
                Frame("Compact – download", 60) { Island(geo, cfg, listOf(download), null, emptyFlow(), noop) }
                Frame("Charging", 60) {
                    Island(geo, cfg, emptyList(), TransientEvent(TransientKind.CHARGING, batteryLevel = 78), emptyFlow(), noop)
                }
                Frame("Expanded – music", 230) { Island(geo, cfg, listOf(music), null, emptyFlow(), noop, requestedExpandedKey = "music") }
                Frame("Expanded – incoming call", 140) { Island(geo, cfg, listOf(call), null, emptyFlow(), noop, requestedExpandedKey = "call") }
                Frame("Expanded – timer", 150) { Island(geo, cfg, listOf(timer), null, emptyFlow(), noop, requestedExpandedKey = "timer") }
                Frame("Alert", 140) {
                    Island(
                        geo, cfg, emptyList(),
                        TransientEvent(TransientKind.ALERT, title = "Sam", text = "Are we still on for dinner tonight?", appIcon = icon),
                        emptyFlow(), noop,
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1500)
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bmp)) }
        val out = File(System.getProperty("user.dir"), "build/island-states.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("Saved ${out.absolutePath}")
    }

    private fun grab(wDp: Int, hDp: Int): Bitmap {
        val root = compose.activity.window.decorView
        val full = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(full)) }
        val d = compose.activity.resources.displayMetrics.density
        return Bitmap.createBitmap(full, 0, 0, (wDp * d).toInt(), (hDp * d).toInt())
    }

    /** Filmstrips of the key motions, sampled every few frames. */
    @Test
    fun renderMotion() {
        compose.mainClock.autoAdvance = false
        val cfg = IslandConfig()
        var acts by mutableStateOf(listOf(music))
        var exp by mutableStateOf<String?>(null)
        compose.setContent {
            Box(
                Modifier.width(400.dp).height(230.dp)
                    .background(Brush.verticalGradient(listOf(Color(0xFF3A5BA0), Color(0xFF8E5BB5))))
            ) { Island(geo, cfg, acts, null, emptyFlow(), noop, requestedExpandedKey = exp) }
        }
        compose.mainClock.advanceTimeBy(1500)
        val frames = mutableListOf<Pair<String, Bitmap>>()
        fun run(label: String, steps: Int, dt: Long) {
            var t = 0L
            repeat(steps) {
                compose.mainClock.advanceTimeBy(dt); t += dt
                frames += "$label  ${t}ms" to grab(400, 230)
            }
        }
        compose.runOnUiThread { exp = "music" }
        run("expand", 12, 48)
        compose.mainClock.advanceTimeBy(1200)
        compose.runOnUiThread { exp = null }
        run("collapse", 6, 48)
        compose.mainClock.advanceTimeBy(1200)
        compose.runOnUiThread { acts = listOf(timer, music) }
        run("split", 9, 64)

        val cols = 3
        val scale = 0.5f
        val fw = (frames[0].second.width * scale).toInt()
        val fh = (frames[0].second.height * scale).toInt()
        val rows = (frames.size + cols - 1) / cols
        val sheet = Bitmap.createBitmap(fw * cols, (fh + 40) * rows, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(0xFF111111.toInt())
        val text = Paint().apply { color = 0xFFFFFFFF.toInt(); textSize = 26f; isAntiAlias = true }
        frames.forEachIndexed { i, (label, bmp) ->
            val x = (i % cols) * fw
            val y = (i / cols) * (fh + 40)
            c.drawText(label, x + 10f, y + 30f, text)
            c.drawBitmap(Bitmap.createScaledBitmap(bmp, fw, fh, true), x.toFloat(), y + 40f, null)
        }
        val out = File(System.getProperty("user.dir"), "build/island-motion.png")
        out.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("Saved ${out.absolutePath}")
    }
}
