package app.gamenative.utils

import app.gamenative.data.GameSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallPathOwnershipTest {
    @Test
    fun `accepts a path claimed by exactly one store id`() {
        val owner = InstallPathOwner(GameSource.GOG, "gog-1", "/games/Same Name")

        assertTrue(hasUniqueInstallPathOwner(owner, listOf(owner)))
    }

    @Test
    fun `rejects same-store ids that resolve to the same path`() {
        val requested = InstallPathOwner(GameSource.GOG, "gog-1", "/games/Same Name")
        val other = InstallPathOwner(GameSource.GOG, "gog-2", "/games/Same Name/.")

        assertFalse(hasUniqueInstallPathOwner(requested, listOf(requested, other)))
    }

    @Test
    fun `rejects colliding Epic catalog ids`() {
        assertSameStoreCollisionIsRejected(GameSource.EPIC, "epic-1", "epic-2")
    }

    @Test
    fun `rejects colliding Amazon product ids`() {
        assertSameStoreCollisionIsRejected(GameSource.AMAZON, "amazon-1", "amazon-2")
    }

    @Test
    fun `does not mix ownership across stores`() {
        val requested = InstallPathOwner(GameSource.GOG, "shared-id", "/games/Same Name")
        val otherStore = InstallPathOwner(GameSource.AMAZON, "shared-id", "/games/Same Name")

        assertTrue(hasUniqueInstallPathOwner(requested, listOf(requested, otherStore)))
    }

    private fun assertSameStoreCollisionIsRejected(
        source: GameSource,
        firstId: String,
        secondId: String,
    ) {
        val requested = InstallPathOwner(source, firstId, "/games/Same Name")
        val other = InstallPathOwner(source, secondId, "/games/Same Name")

        assertFalse(hasUniqueInstallPathOwner(requested, listOf(requested, other)))
    }
}
