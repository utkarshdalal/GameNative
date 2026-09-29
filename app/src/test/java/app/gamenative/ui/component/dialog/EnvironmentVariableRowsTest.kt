package app.gamenative.ui.component.dialog

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.gamenative.utils.ModDllOverrides
import com.alorma.compose.settings.ui.SettingsGroup
import com.winlator.core.envvars.EnvVars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EnvironmentVariableRowsTest {
    @get:Rule val compose = createComposeRule()

    private val saved = mutableStateOf("")
    private val inspection = mutableStateOf<ModDllOverrides.Inspection?>(detected())

    private fun detected(
        names: List<String> = listOf("winhttp"),
        registry: ModDllOverrides.RegistryOverrides = ModDllOverrides.RegistryOverrides(),
    ) = ModDllOverrides.Inspection(null, names, registry)

    private fun show() {
        compose.setContent {
            MaterialTheme {
                SettingsGroup {
                    EnvironmentVariableRows(saved.value, inspection.value, "STEAM_1625450") { saved.value = it }
                }
            }
        }
    }

    @Test fun automaticOverrideUsesOneNormalEditableRowWithoutSavingOrCustomText() {
        show()
        compose.onAllNodesWithText("WINEDLLOVERRIDES").assertCountEquals(1)
        compose.onNode(hasSetTextAction() and hasText("winhttp=n,b")).assertExists()
        compose.onNodeWithContentDescription("Presets").assertExists()
        compose.onNodeWithContentDescription("Delete variable").assertDoesNotExist()
        compose.onNodeWithText("Detected:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Includes saved overrides", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals("", saved.value) }
    }

    @Test fun editingAndDeletingOtherVariablesNeverPersistsGeneratedOverrides() {
        saved.value = "WRAPPER_MAX_IMAGE_COUNT=0"
        show()
        compose.onNode(hasSetTextAction() and hasText("0")).performTextReplacement("2")
        compose.runOnIdle { assertEquals("WRAPPER_MAX_IMAGE_COUNT=2", saved.value) }
        compose.onNodeWithText("winhttp=n,b").assertExists()
        compose.onNodeWithContentDescription("Delete variable").performClick()
        compose.runOnIdle { assertEquals("", saved.value) }
        compose.onNodeWithText("winhttp=n,b").assertExists()
    }

    @Test fun unrelatedEditPreservesOnlyOriginalManualOverridesAndTheirSpelling() {
        saved.value = EnvVars().apply {
            put("WINEDLLOVERRIDES", " ICU = native ;")
            put("WRAPPER_MAX_IMAGE_COUNT", "0")
        }.toString()
        show()
        compose.onNode(hasSetTextAction() and hasText("0")).performTextReplacement("3")
        compose.runOnIdle {
            val env = EnvVars(saved.value)
            assertEquals(" ICU = native ;", env.get("WINEDLLOVERRIDES"))
            assertEquals("3", env.get("WRAPPER_MAX_IMAGE_COUNT"))
        }
        compose.onNodeWithText(" ICU = native ;winhttp=n,b").assertExists()
    }

    @Test fun clearingThenTypingAnOverrideDoesNotRefillBetweenKeystrokes() {
        show()
        compose.onNode(hasSetTextAction()).performTextReplacement("")
        compose.runOnIdle {
            assertEquals("", EnvVars(saved.value).get("WINEDLLOVERRIDES"))
            assertFalse(saved.value.contains("winhttp"))
        }
        compose.onNode(hasSetTextAction()).performTextInput("winhttp=b")
        compose.runOnIdle {
            val value = EnvVars(saved.value).get("WINEDLLOVERRIDES")
            assertEquals("winhttp=b", value)
            assertEquals(value, inspection.value!!.merge(value).value)
        }
        compose.onNodeWithText("winhttp=b").assertExists()
        compose.onNodeWithContentDescription("Delete variable").assertExists()
    }

    @Test fun deletingManualOverrideRestoresAutomaticRowWithoutSavingIt() {
        saved.value = "WINEDLLOVERRIDES=winhttp=b"
        show()
        compose.onNodeWithContentDescription("Delete variable").performClick()
        compose.runOnIdle { assertEquals("", saved.value) }
        compose.onNodeWithText("winhttp=n,b").assertExists()
        compose.onNodeWithContentDescription("Delete variable").assertDoesNotExist()
    }

    @Test fun modRemovalDropsOnlyAutomaticEntriesAndLeavesManualChoices() {
        saved.value = "WINEDLLOVERRIDES=icu=n"
        show()
        compose.onNodeWithText("icu=n;winhttp=n,b").assertExists()
        compose.runOnIdle { inspection.value = detected(emptyList()) }
        compose.onNodeWithText("icu=n").assertExists()
        compose.runOnIdle { assertEquals("WINEDLLOVERRIDES=icu=n", saved.value) }
    }

    @Test fun removingTheOnlyModRemovesTheAutomaticRow() {
        show()
        compose.onNodeWithText("winhttp=n,b").assertExists()
        compose.runOnIdle { inspection.value = detected(emptyList()) }
        compose.onNodeWithText("WINEDLLOVERRIDES").assertDoesNotExist()
        compose.runOnIdle { assertEquals("", saved.value) }
    }

    @Test fun unsupportedLaunchHidesAutomaticEntriesButKeepsManualRows() {
        saved.value = "WINEDLLOVERRIDES=icu=n"
        show()
        compose.runOnIdle { inspection.value = null }
        compose.onNodeWithText("icu=n").assertExists()
        compose.onNodeWithText("icu=n;winhttp=n,b").assertDoesNotExist()
    }

    @Test fun registryChoicesDoNotBecomeEnvironmentOverrides() {
        inspection.value = detected(registry = ModDllOverrides.RegistryOverrides(global = mapOf("winhttp" to "b")))
        show()
        compose.onNodeWithText("WINEDLLOVERRIDES").assertDoesNotExist()
        compose.runOnIdle { assertEquals("", saved.value) }
    }

    @Test fun editingWholeOverrideValueMakesTheEditedValueManual() {
        saved.value = "WINEDLLOVERRIDES=icu=n"
        show()
        compose.onNode(hasSetTextAction()).performTextReplacement("icu=b;winhttp=b")
        compose.runOnIdle { inspection.value = detected(emptyList()) }
        compose.onNodeWithText("icu=b;winhttp=b").assertExists()
        compose.runOnIdle { assertEquals("icu=b;winhttp=b", EnvVars(saved.value).get("WINEDLLOVERRIDES")) }
    }
}
