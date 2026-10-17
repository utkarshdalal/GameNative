package app.gamenative.utils

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the GPU card contract of a launch configuration, which decides whether a
 * container keeps its stored card or has the override applied: reporting an identity
 * other than the one that wrote the pipeline cache makes applications discard it.
 *
 * screenSize and suspendPolicy are supplied explicitly so parsing never reaches
 * PluviaApp or PrefManager, which need an Android runtime.
 */
class IntentLaunchManagerTest {

    private fun config(videoPciDeviceId: Int?): String {
        val json = JSONObject()
            .put("screenSize", "1280x720")
            .put("suspendPolicy", "auto")
        videoPciDeviceId?.let { json.put("videoPciDeviceID", it) }
        return json.toString()
    }

    @Test
    fun `omitted device id parses as the Device entry`() {
        assertEquals(0, IntentLaunchManager.parseContainerConfig(config(null)).videoPciDeviceID)
    }

    @Test
    fun `explicit zero parses as the Device entry`() {
        assertEquals(0, IntentLaunchManager.parseContainerConfig(config(0)).videoPciDeviceID)
    }

    @Test
    fun `positive device id is preserved`() {
        assertEquals(4480, IntentLaunchManager.parseContainerConfig(config(4480)).videoPciDeviceID)
    }

    @Test
    fun `merge keeps the stored card when the override omits the id`() {
        val base = IntentLaunchManager.parseContainerConfig(config(4480))
        val override = IntentLaunchManager.parseContainerConfig(config(null))
        assertEquals(4480, IntentLaunchManager.mergeConfigurations(base, override).videoPciDeviceID)
    }

    @Test
    fun `merge applies the card selected by the override`() {
        val base = IntentLaunchManager.parseContainerConfig(config(4480))
        val override = IntentLaunchManager.parseContainerConfig(config(1728))
        assertEquals(1728, IntentLaunchManager.mergeConfigurations(base, override).videoPciDeviceID)
    }

    @Test
    fun `merge keeps the stored card when the override selects the Device entry`() {
        // Current limitation, pinned rather than fixed: the parsed configuration has no
        // way to say "field absent" apart from 0, so an override cannot force the
        // sentinel against a container that already has a card.
        val base = IntentLaunchManager.parseContainerConfig(config(4480))
        val override = IntentLaunchManager.parseContainerConfig(config(0))
        assertEquals(4480, IntentLaunchManager.mergeConfigurations(base, override).videoPciDeviceID)
    }
}
