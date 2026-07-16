package app.gamenative.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NexusUrlParserTest {
    @Test
    fun parse_modPage_extractsGameAndMod() {
        val parsed = NexusUrlParser.parse("https://www.nexusmods.com/skyrimspecialedition/mods/12345")

        assertEquals(NexusModReference("skyrimspecialedition", 12345L, null), parsed)
    }

    @Test
    fun parse_fileQuery_extractsFileId() {
        val parsed = NexusUrlParser.parse(
            "https://www.nexusmods.com/cyberpunk2077/mods/42?tab=files&file_id=9876",
        )

        assertEquals(NexusModReference("cyberpunk2077", 42L, 9876L), parsed)
    }

    @Test
    fun parse_nxmUrl_extractsFileId() {
        val parsed = NexusUrlParser.parse("nxm://fallout4/mods/100/files/200")

        assertEquals(NexusModReference("fallout4", 100L, 200L), parsed)
    }

    @Test
    fun parse_nonNexusUrl_returnsNull() {
        assertNull(NexusUrlParser.parse("https://example.com/skyrim/mods/1"))
    }

    @Test
    fun parse_collectionUrl_extractsGameSlugAndRevision() {
        val parsed = NexusCollectionUrlParser.parse(
            "https://next.nexusmods.com/skyrimspecialedition/collections/abc123/revisions/7",
        )

        assertEquals(NexusCollectionReference("skyrimspecialedition", "abc123", 7), parsed)
    }

    @Test
    fun parse_collectionQueryRevision_extractsRevision() {
        val parsed = NexusCollectionUrlParser.parse(
            "https://www.nexusmods.com/cyberpunk2077/collections/redmod?revision=12",
        )

        assertEquals(NexusCollectionReference("cyberpunk2077", "redmod", 12), parsed)
    }
}
