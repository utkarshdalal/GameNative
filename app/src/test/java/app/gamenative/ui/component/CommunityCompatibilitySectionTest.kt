package app.gamenative.ui.component

import android.app.Application
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.gamenative.data.CommunityCompatibilitySummary
import app.gamenative.data.CommunityCompatibilityVerdict
import app.gamenative.data.CommunityEvidenceTier
import app.gamenative.data.CommunityVerdictSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class CommunityCompatibilitySectionTest {
    @get:Rule val compose = createComposeRule()

    private val summary = CommunityCompatibilitySummary(
        CommunityCompatibilityVerdict.SHOULD_WORK, CommunityEvidenceTier.SAME_GPU,
        sessionCount = 101, medianFps = 47, verdictLoaded = true,
        serverState = "Works",
        verdictSource = CommunityVerdictSource.SERVER,
    )

    @Test fun startsCompactAndDetailsToggleDoesNotRefreshOrBrowse() {
        var requests = 0
        val game = mutableStateOf("First")
        render(game = game, onAction = { requests++ })
        compose.onNodeWithText("Runs").assertIsDisplayed()
        compose.onNodeWithText("Based on 101 recorded sessions using your GPU.").assertIsDisplayed()
        compose.onNodeWithText("Median 47 FPS reported using your GPU.").assertDoesNotExist()
        compose.onNodeWithText("Details").performScrollTo().performClick()
        compose.onNodeWithText("Median 47 FPS reported using your GPU.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Details").performScrollTo().performClick()
        compose.onNodeWithText("Median 47 FPS reported using your GPU.").assertDoesNotExist()
        compose.onNodeWithText("Details").performScrollTo().performClick()
        compose.runOnIdle { game.value = "Second" }
        compose.onNodeWithText("Median 47 FPS reported using your GPU.").assertDoesNotExist()
        assertEquals(0, requests)
    }

    @Test fun cautionsRemainVisibleWhenDetailsAreCollapsed() {
        render(summary.copy(verdict = CommunityCompatibilityVerdict.MIXED))
        compose.onNodeWithText("Unreliable results; working gameplay isn’t confirmed.").assertIsDisplayed()
        compose.onNodeWithText("Median 47 FPS reported using your GPU.").assertDoesNotExist()
    }

    @Test fun manualRefreshIsAbsentAndRetryIsOnlyAvailableAfterFailure() {
        var requests = 0
        val loading = mutableStateOf(false)
        val loadError = mutableStateOf(false)
        render(loading = loading, loadError = loadError, onAction = {
            requests++
            loading.value = true
        })
        compose.onNodeWithContentDescription("Refresh compatibility").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.runOnIdle { loadError.value = true }
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals(1, requests)
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.runOnIdle {
            loadError.value = false
            loading.value = false
        }
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithContentDescription("Refresh compatibility").assertDoesNotExist()
    }

    @Test fun chipsetSessionsAndFpsDoNotRelabelGpuConfigEvidence() {
        render(
            summary.copy(
                evidenceTier = CommunityEvidenceTier.SAME_SOC,
                reportCount = 3, reportEvidenceTier = CommunityEvidenceTier.SAME_GPU,
                hasDetailedReports = true, detailsLoaded = true,
            ),
        )
        compose.onNodeWithText("Runs").assertIsDisplayed()
        compose.onNodeWithText("Based on 101 recorded sessions using the same chipset.").assertIsDisplayed()
        compose.onNodeWithText("Details").performScrollTo().performClick()
        compose.onNodeWithText("3 rated shared configs using your GPU").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Median 47 FPS reported using the same chipset.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Median 47 FPS reported using your GPU.").assertDoesNotExist()
    }

    private fun render(
        value: CommunityCompatibilitySummary = summary,
        game: State<String> = mutableStateOf("Game"),
        onAction: () -> Unit = {},
        loading: State<Boolean> = mutableStateOf(false),
        loadError: State<Boolean> = mutableStateOf(false),
    ) {
        compose.setContent {
            MaterialTheme {
                CommunityCompatibilitySection(
                    gameKey = game.value, summary = value, loading = loading.value, loadError = loadError.value,
                    onRetry = onAction, onViewReports = onAction, modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}
