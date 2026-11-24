package jdsp.irbuilder.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import jdsp.irbuilder.ui.Dialogs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The phone app starts in portrait on a Galaxy S25 Ultra-sized screen and every tool opens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-port-xxhdpi")
class AppLaunchTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun startsInPortraitAndEveryToolOpens() {
        compose.onNodeWithText("Choose a tool").assertIsDisplayed()
        for (tool in listOf("Band Align", "Phase IRs", "Crossover Finder", "EQ Wizard")) {
            compose.onNodeWithText("Start $tool").performScrollTo().performClick()
            compose.onNodeWithText("$tool · change").assertIsDisplayed()
            // Each step opens.
            compose.onNodeWithText("Checks").performClick()
            compose.onNodeWithText("Measurement setup", substring = true, ignoreCase = true).assertExists()
            compose.onNodeWithText("$tool · change").performClick()
        }
    }

    @Test
    fun savingWritesToTheCacheFirst() {
        var got: File? = null
        compose.runOnUiThread { Dialogs.saveFile("Save", "tune.jirb") { got = it } }
        val f = got!!
        assertEquals("tune.jirb", f.name)
        assertTrue(f.path.startsWith(compose.activity.cacheDir.path))
        var dir: File? = null
        compose.runOnUiThread { Dialogs.chooseFolder("Export", null) { dir = it } }
        assertTrue(dir!!.isDirectory && dir!!.path.startsWith(compose.activity.cacheDir.path))
    }

    private fun uriFor(f: File) = android.net.Uri.fromFile(f)

    /** Runs the main thread's queue until [done]: background copies hand their results back there. */
    private fun await(done: () -> Boolean) {
        repeat(100) {
            if (done()) return
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(50)
        }
        throw AssertionError("timed out")
    }

    @Test
    fun picksWithTheSameNameDontOverwriteEachOther() {
        val a = compose.activity
        val one = File(a.filesDir, "src/one/driver_L_low.txt").apply { parentFile!!.mkdirs(); writeText("first") }
        val two = File(a.filesDir, "src/two/driver_L_low.txt").apply { parentFile!!.mkdirs(); writeText("second") }
        var got: List<File>? = null
        compose.runOnUiThread {
            Dialogs.reset()
            Dialogs.openFiles("Add", listOf("txt")) { got = it }
            Dialogs.opened(a, listOf(uriFor(one), uriFor(two)))
        }
        await { got != null }
        val files = got!!
        assertEquals(listOf("driver_L_low.txt", "driver_L_low.txt"), files.map { it.name })
        assertEquals(2, files.map { it.path }.toSet().size)
        assertEquals(listOf("first", "second"), files.map { it.readText() })
    }

    @Test
    fun aPickDeliveredAfterTheAppWasRestartedStillLands() {
        val a = compose.activity
        val f = File(a.filesDir, "src/restart/driver_c_L_combined.txt").apply { parentFile!!.mkdirs(); writeText("x") }
        val saved = android.os.Bundle()
        compose.runOnUiThread {
            Dialogs.reset()
            Dialogs.openFiles("Add", listOf("txt")) { error("the old screen is gone") }
            Dialogs.save(saved)
            // The process died: nothing pending in memory, the activity comes back with its saved state.
            Dialogs.reset()
            Dialogs.attach(a, saved)
            Dialogs.opened(a, listOf(uriFor(f)))
        }
        await { a.state.measurements.isNotEmpty() }
        assertEquals("driver_c_L_combined.txt", File(a.state.measurements.single().path).name)
    }

    @Test
    fun aSaveDeliveredAfterTheAppWasRestartedWritesTheFile() {
        val a = compose.activity
        val written = File(a.cacheDir, "out/x/tune.jirb").apply { parentFile!!.mkdirs(); writeText("{project}") }
        val target = File(a.filesDir, "chosen/tune.jirb").apply { parentFile!!.mkdirs(); writeText("") }
        val saved = android.os.Bundle()
        compose.runOnUiThread {
            Dialogs.reset()
            Dialogs.written(written)
            Dialogs.save(saved)
            Dialogs.reset()
            Dialogs.attach(a, saved)
            Dialogs.created(a, uriFor(target))
        }
        await { target.readText().isNotEmpty() }
        assertEquals("{project}", target.readText())
    }
}

