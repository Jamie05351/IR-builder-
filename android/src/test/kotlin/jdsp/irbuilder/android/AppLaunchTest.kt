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
}
