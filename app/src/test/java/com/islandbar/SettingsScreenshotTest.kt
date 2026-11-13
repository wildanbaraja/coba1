package com.islandbar

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ActivityScenario
import com.islandbar.ui.settings.MainActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** Renders the One UI-style settings screen (light and dark) into build/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SettingsScreenshotTest {

    private fun render(name: String) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ShadowLooper.idleMainLooper(1200, java.util.concurrent.TimeUnit.MILLISECONDS)
            scenario.onActivity { a ->
                val root = a.window.decorView
                val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bmp))
                val out = File(System.getProperty("user.dir"), "build/$name.png")
                out.parentFile?.mkdirs()
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                println("Saved ${out.absolutePath}")
            }
        }
    }

    @Test
    @Config(qualifiers = "w384dp-h2300dp-notnight-xxhdpi")
    fun light() = render("settings-light")

    @Test
    @Config(qualifiers = "w384dp-h2300dp-night-xxhdpi")
    fun dark() = render("settings-dark")
}
