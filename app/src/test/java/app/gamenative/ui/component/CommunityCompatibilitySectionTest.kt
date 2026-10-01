package app.gamenative.ui.component

import android.app.Application
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.gamenative.data.CommunityCompatibilitySummary
import app.gamenative.data.CommunityCompatibilityVerdict
import app.gamenative.data.CommunityConfidenceCaution
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
        serverState = "Works", serverTier = "gpu", serverTierKey = "Adreno (TM) 830",
        verdictSource = CommunityVerdictSource.SERVER,
    )

    @Test fun startsCompactAndDetailsToggleDoesNotRefreshOrBrowse() {
        var requests = 0
        val game = mutableStateOf("First")
        compose.setContent {
            MaterialTheme {
                CommunityCompatibilitySection(
                    gameKey = game.value, summary = summary, loading = false, loadError = false,
                    onRetry = { requests++ }, onViewReports = { requests++ },
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }
        compose.onNodeWithText("Works").assertIsDisplayed()
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
        compose.setContent {
            MaterialTheme {
                CommunityCompatibilitySection(
                    gameKey = "Game",
                    summary = summary.copy(verdict = CommunityCompatibilityVerdict.MIXED, ratingCaution = true, isCachedResultStale = true),
                    loading = false, loadError = false, onRetry = {}, onViewReports = {},
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }
        compose.onNodeWithText("Unreliable results; working gameplay isn’t confirmed.").assertIsDisplayed()
        compose.onNodeWithText("Showing the last known result while awaiting a successful refresh.").assertIsDisplayed()
        compose.onNodeWithText("Most rated-device feedback is not positive. Check the details before installing.").assertIsDisplayed()
        compose.onNodeWithText("Median 47 FPS reported using your GPU.").assertDoesNotExist()
    }

    @Test fun conflictReasonStaysVisibleWithoutClaimingGameplayIsUnconfirmedOrDuplicatingIt() {
        render(
            summary.copy(
                verdict = CommunityCompatibilityVerdict.MIXED,
                confidenceCaution = CommunityConfidenceCaution.CONFLICTING_FEEDBACK,
            ),
        )
        compose.onNodeWithText("Community feedback is mixed. Some setups may not work.").assertIsDisplayed()
        compose.onNodeWithText("Unreliable results", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Details").performScrollTo().performClick()
        compose.onAllNodesWithText("Community feedback is mixed.", substring = true).assertCountEquals(1)
    }

    @Test fun limitedEvidenceDoesNotDuplicateTheZeroPositiveFeedbackWarning() {
        render(
            summary.copy(
                verdict = CommunityCompatibilityVerdict.MAY_WORK, confidenceCaution = CommunityConfidenceCaution.LIMITED_EVIDENCE,
                limitedRatingFeedback = true,
            ),
        )
        compose.onNodeWithText("Limited rating feedback; none positive so far.").assertIsDisplayed()
        compose.onNodeWithText("Not enough matching evidence", substring = true).assertDoesNotExist()
    }

    @Test fun chipsetSessionsAndFpsDoNotRelabelGpuConfigEvidence() {
        render(
            summary.copy(
                evidenceTier = CommunityEvidenceTier.SAME_SOC, serverTier = "soc", serverTierKey = "Chipset",
                reportCount = 3, reportEvidenceTier = CommunityEvidenceTier.SAME_GPU,
                hasDetailedReports = true, detailsLoaded = true,
            ),
        )
        compose.onNodeWithText("Works").assertIsDisplayed()
        compose.onNodeWithText("Based on 101 recorded sessions using the same chipset.").assertIsDisplayed()
        compose.onNodeWithText("Details").performScrollTo().performClick()
        compose.onNodeWithText("3 rated shared configs using your GPU").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Median 47 FPS reported using the same chipset.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Median 47 FPS reported using your GPU.").assertDoesNotExist()
    }

    private fun render(value: CommunityCompatibilitySummary) {
        compose.setContent {
            MaterialTheme {
                CommunityCompatibilitySection(
                    gameKey = "Game", summary = value, loading = false, loadError = false,
                    onRetry = {}, onViewReports = {}, modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}
