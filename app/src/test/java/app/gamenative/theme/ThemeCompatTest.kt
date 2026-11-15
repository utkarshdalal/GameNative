package app.gamenative.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeCompatTest {

    private val verifiedId = "6f1c2a3e-0000-4000-8000-000000000001"
    private val incompatibleId = "6f1c2a3e-0000-4000-8000-000000000002"
    private val keyboardId = "6f1c2a3e-0000-4000-8000-000000000003"

    private val table = ThemeCompat.parse(
        """
        {
          "version": 1,
          "themes": {
            "$verifiedId": "verified",
            "$incompatibleId": "incompatible",
            "$keyboardId": "verified",
            "6f1c2a3e-0000-4000-8000-000000000009": "maybe"
          },
          "incompatibleTargets": ["Keyboard", "Lock Screen", " Desktop-Store "]
        }
        """.trimIndent(),
    )

    private fun theme(id: String, target: String? = null, targets: List<String> = emptyList()) =
        StoreTheme(id = id, name = "x", target = target, targets = targets)

    @Test
    fun listedIdsGetTheirStatusAndOthersAreUnverified() {
        assertEquals(ThemeCompatStatus.VERIFIED, table.statusOf(theme(verifiedId)))
        assertEquals(ThemeCompatStatus.INCOMPATIBLE, table.statusOf(theme(incompatibleId, target = "Library")))
        assertEquals(ThemeCompatStatus.UNVERIFIED, table.statusOf(theme("6f1c2a3e-0000-4000-8000-000000000009")))
        assertEquals(ThemeCompatStatus.UNVERIFIED, table.statusOf(theme("unknown", target = "Library")))
    }

    @Test
    fun themeWithOnlyIncompatibleTargetsIsIncompatible() {
        assertEquals(ThemeCompatStatus.INCOMPATIBLE, table.statusOf(theme("a", target = "keyboard")))
        assertEquals(ThemeCompatStatus.INCOMPATIBLE, table.statusOf(theme("b", target = "Lock Screen", targets = listOf("DESKTOP-STORE"))))
        assertEquals(ThemeCompatStatus.UNVERIFIED, table.statusOf(theme("c", target = "Keyboard", targets = listOf("Library"))))
        assertEquals(ThemeCompatStatus.UNVERIFIED, table.statusOf(theme("d", target = " ")))
    }

    @Test
    fun explicitIdEntryWinsOverTargets() {
        assertEquals(ThemeCompatStatus.VERIFIED, table.statusOf(theme(keyboardId, target = "Keyboard")))
    }

    @Test
    fun missingOrGarbledFileLeavesEverythingUnverified() {
        for (json in listOf("", "not json", "[]", "{\"themes\": []}", "{\"themes\": {\"$verifiedId\": 3}, \"incompatibleTargets\": \"Keyboard\"}")) {
            val parsed = ThemeCompat.parse(json)
            assertTrue(parsed.themes.isEmpty())
            assertTrue(parsed.incompatibleTargets.isEmpty())
            assertEquals(ThemeCompatStatus.UNVERIFIED, parsed.statusOf(theme(verifiedId, target = "Keyboard")))
        }
    }
}
