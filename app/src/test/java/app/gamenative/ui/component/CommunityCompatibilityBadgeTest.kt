package app.gamenative.ui.component

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.gamenative.data.CommunityCompatibilityVerdict
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommunityCompatibilityBadgeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowBadgesKeepFullAccessibleVerdictAndExpandAgainWhenSpaceReturns() {
        val availableWidth = mutableStateOf(48.dp)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(availableWidth.value)) {
                    CommunityCompatibilityBadge(
                        CommunityCompatibilityVerdict.SHOULD_WORK,
                        modifier = Modifier.testTag("badge"), showLabel = true,
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Runs").assertIsDisplayed()
        compose.onNodeWithTag("badge").assertWidthIsEqualTo(30.dp)
        compose.runOnIdle { availableWidth.value = 240.dp }
        compose.onNodeWithContentDescription("Runs").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("badge").fetchSemanticsNode().boundsInRoot.width > with(compose.density) { 30.dp.toPx() })
        compose.runOnIdle { availableWidth.value = 48.dp }
        compose.onNodeWithTag("badge").assertWidthIsEqualTo(30.dp)
    }

    @Test fun largeTextAndRtlUseIconFallbackWithoutShrinkingTheFont() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                MaterialTheme {
                    Box(Modifier.width(100.dp)) {
                        CommunityCompatibilityBadge(
                            CommunityCompatibilityVerdict.MIXED,
                            modifier = Modifier.testTag("badge"), showLabel = true,
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("badge").assertWidthIsEqualTo(30.dp)
        compose.onNodeWithContentDescription("Unreliable").assertIsDisplayed()
    }

    @Test fun iconFallbackPreservesLoadingAndFailureDescriptions() {
        val failed = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(48.dp)) {
                    CommunityCompatibilityBadge(
                        CommunityCompatibilityVerdict.UNKNOWN, modifier = Modifier.testTag("badge"),
                        showLabel = true, verdictLoaded = false, checking = !failed.value, loadFailed = failed.value,
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Checking…").assertIsDisplayed()
        compose.onNodeWithTag("badge").assertWidthIsEqualTo(30.dp)
        compose.runOnIdle { failed.value = true }
        compose.onNodeWithContentDescription("Unavailable").assertIsDisplayed()
        compose.onNodeWithTag("badge").assertWidthIsEqualTo(30.dp)
    }
}
