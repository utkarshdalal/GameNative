package com.winlator.container

import android.app.Application
import androidx.compose.runtime.saveable.SaverScope
import com.winlator.inputcontrols.OnScreenControlsVisibility
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ContainerControlsVisibilityTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun hiddenChoiceSurvivesDiskReloadWithoutChangingMouseMode() {
        val container = Container("STEAM_1").apply {
            rootDir = temp.newFolder()
            onScreenControlsVisibility = OnScreenControlsVisibility.HIDDEN
            setTouchscreenMode(false)
        }
        assertTrue(container.saveDataChecked())

        val restored = Container("STEAM_1").apply {
            loadData(JSONObject(container.configFile.readText()))
        }
        assertEquals(OnScreenControlsVisibility.HIDDEN, restored.onScreenControlsVisibility)
        assertFalse(restored.isTouchscreenMode)
        assertFalse(restored.isDisableMouseInput)
        assertEquals(OnScreenControlsVisibility.AUTOMATIC, Container("STEAM_2").onScreenControlsVisibility)
    }

    @Test
    fun oldOrInvalidConfigsFallBackToAutomaticEvenWhenReloadingTheSameContainer() {
        val container = Container("STEAM_1")
        for (config in listOf(JSONObject(), JSONObject().put("onScreenControlsVisibility", "unknown"))) {
            container.onScreenControlsVisibility = OnScreenControlsVisibility.SHOWN
            container.loadData(config)
            assertEquals(OnScreenControlsVisibility.AUTOMATIC, container.onScreenControlsVisibility)
        }
    }

    @Test
    fun dialogStateRestoresEachChoiceAndAcceptsOlderSavedState() {
        val scope = SaverScope { true }
        for (visibility in OnScreenControlsVisibility.values()) {
            val data = ContainerData(onScreenControlsVisibility = visibility)
            val saved = with(ContainerData.Saver) { scope.save(data) }!!
            assertEquals(visibility, ContainerData.Saver.restore(saved)!!.onScreenControlsVisibility)
        }

        val saved = with(ContainerData.Saver) { scope.save(ContainerData()) } as List<*>
        val oldState = saved.chunked(2).filter { it[0] != "onScreenControlsVisibility" }.flatten()
        assertEquals(OnScreenControlsVisibility.AUTOMATIC, ContainerData.Saver.restore(oldState)!!.onScreenControlsVisibility)
    }
}
