package app.gamenative.utils

import android.app.Application
import app.gamenative.data.CommunityCompatibilityClassifier
import app.gamenative.data.CommunityCompatibilityVerdict
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class CompatibilityAndroidParsingTest {
    @Test fun androidJsonNullAndMalformedHardwareKeysNeverProduceDecisiveBadges() {
        for (key in listOf("null", "\"\"", "\"null\"", "\" NULL \"", "42", "true", "{}", "[]")) {
            for (state in listOf("Great", "Works", "Broken", "Unreliable")) {
                val response = GameCompatibilityService.parseCompatibilityResponse(
                    """{"results":{"Game":{"state":"$state","tier":"gpu","tiers":{"gpu":{
                        "key":$key,"sessions":20,"playable":18,"playableRate":0.9,"ratedDevices":10,"okDevices":9
                    }}}}}""",
                    listOf("Game"),
                ).getValue("Game")
                assertFalse(response.tiers.getValue("gpu").hasHardwareKey)
                assertEquals(
                    CommunityCompatibilityVerdict.UNKNOWN,
                    CommunityCompatibilityClassifier.fromCompatibilityResponse(response).verdict,
                )
            }
        }
    }

    @Test fun explicitNullStateDoesNotBecomeTheStringNull() {
        val result = GameCompatibilityService.parseCompatibilityResponse("""{"Game":{"state":null}}""", listOf("Game"))
        assertTrue(result.isEmpty())
    }
}
