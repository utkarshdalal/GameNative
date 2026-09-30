package app.gamenative.mods

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NexusCollectionManifestParserTest {
    @Test
    fun classify_marksExecutableNexusFilesForPlacementReview() {
        val data = NexusCollectionManifestParser.classify(
            item = JSONObject(),
            modId = 1,
            fileId = 2,
            modName = "Tool packaged as SFX",
            fileName = "tool-installer.exe",
        )

        assertEquals(NexusCollectionInstallClassification.NEEDS_PLACEMENT, data.classification)
    }
}
