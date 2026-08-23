package app.gamenative.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun parse_nxmUrlWithMalformedPercentEscape_returnsNull() {
        assertNull(
            NexusUrlParser.parse(
                "nxm://newvegas/mods/58277/files/123456?key=%ZZ&expires=4000000000",
            ),
        )
    }

    @Test
    fun parseNxmDownloadGrant_preservesLiteralPlusInSignedKey() {
        val result = NexusUrlParser.parseNxmDownloadGrant(
            input = "nxm://newvegas/mods/12/files/34?key=signed+grant%2Bpart&expires=200&user_id=99",
            nowEpochSeconds = 100L,
        )

        val reference = (result as NexusUrlParser.NxmDownloadGrantResult.Valid).reference
        assertEquals("signed+grant+part", reference.downloadAuthorization?.key)
        assertEquals(99L, reference.downloadAuthorization?.userId)
    }

    @Test
    fun parseNxmReference_preservesLiteralPlusInSignedKey() {
        val reference = NexusUrlParser.parse(
            "nxm://newvegas/mods/12/files/34?key=signed+grant%2Bpart&expires=200&user_id=99",
        )

        assertEquals("signed+grant+part", reference?.downloadAuthorization?.key)
    }

    @Test
    fun parseNxmDownloadGrant_rejectsExpiredGrantSeparately() {
        val result = NexusUrlParser.parseNxmDownloadGrant(
            input = "nxm://newvegas/mods/12/files/34?key=signed&expires=100&user_id=99",
            nowEpochSeconds = 100L,
        )

        assertEquals(NexusUrlParser.NxmDownloadGrantResult.Expired, result)
    }

    @Test
    fun parseNxmDownloadGrant_rejectsMissingOrDuplicateAccountBoundFields() {
        val missingUser = NexusUrlParser.parseNxmDownloadGrant(
            input = "nxm://newvegas/mods/12/files/34?key=signed&expires=200",
            nowEpochSeconds = 100L,
        )
        val duplicateKey = NexusUrlParser.parseNxmDownloadGrant(
            input = "nxm://newvegas/mods/12/files/34?key=one&key=two&expires=200&user_id=99",
            nowEpochSeconds = 100L,
        )

        assertEquals(NexusUrlParser.NxmDownloadGrantResult.Malformed, missingUser)
        assertEquals(NexusUrlParser.NxmDownloadGrantResult.Malformed, duplicateKey)
    }

    @Test
    fun parseNxmDownloadGrant_rejectsNonCanonicalAuthorityPathAndFragment() {
        val malformed = listOf(
            "nxm://user@newvegas/mods/12/files/34?key=signed&expires=200&user_id=99",
            "nxm://newvegas:443/mods/12/files/34?key=signed&expires=200&user_id=99",
            "nxm://newvegas/extra/mods/12/files/34?key=signed&expires=200&user_id=99",
            "nxm://newvegas/mods/12/files/34/extra?key=signed&expires=200&user_id=99",
            "nxm://newvegas/mods/12/files/34?key=signed&expires=200&user_id=99#fragment",
        )

        assertTrue(
            malformed.all {
                NexusUrlParser.parseNxmDownloadGrant(it, nowEpochSeconds = 100L) ==
                    NexusUrlParser.NxmDownloadGrantResult.Malformed
            },
        )
    }

    @Test
    fun parse_nonNexusUrl_returnsNull() {
        assertNull(NexusUrlParser.parse("https://example.com/skyrim/mods/1"))
    }

    @Test
    fun parseNxmReference_preservesLegacyModOnlyAndQueryFileForms() {
        val modOnly = NexusUrlParser.parse("nxm://skyrimspecialedition/mods/30379")
        val queryFile = NexusUrlParser.parse(
            "nxm://skyrimspecialedition/mods/30379?file_id=92910",
        )

        assertEquals(30379L, modOnly?.modId)
        assertNull(modOnly?.fileId)
        assertEquals(92910L, queryFile?.fileId)
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
