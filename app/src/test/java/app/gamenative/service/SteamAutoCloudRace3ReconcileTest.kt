package app.gamenative.service

import app.gamenative.data.UserFileInfo
import app.gamenative.enums.PathType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// pure-jvm regression lock for the Race-3 reconcile cache builder (F5.1). exercises
// buildRace3CacheEntries directly with a filename keyOf so it needs none of the SteamUtils /
// abs-path machinery the real call site uses. proves the two load-bearing properties:
//   - local-only files (failed uploads cloud never recorded) are EXCLUDED → next launch re-uploads
//   - in-cloud files keep cloud's sha when the local PUT failed → next launch re-detects + retries
class SteamAutoCloudRace3ReconcileTest {

    private fun file(name: String, sha: ByteArray): UserFileInfo =
        UserFileInfo(
            root = PathType.GameInstall,
            path = "",
            filename = name,
            timestamp = 0L,
            sha = sha,
        )

    private val keyOf: (UserFileInfo) -> String = { it.filename }

    @Test
    fun localOnlyFile_excludedFromCache() {
        val local = listOf(
            file("a.sav", byteArrayOf(1)),
            file("local_only.sav", byteArrayOf(9)), // failed upload — cloud has no record
        )
        val remote = mapOf("a.sav" to byteArrayOf(1))

        val cache = SteamAutoCloud.buildRace3CacheEntries(local, remote, keyOf)

        assertEquals("only the in-cloud file is cached", 1, cache.size)
        assertTrue("local-only file excluded", cache.none { it.filename == "local_only.sav" })
    }

    @Test
    fun inCloudMatchingSha_keptAsIs() {
        val sha = byteArrayOf(1, 2, 3)
        val local = listOf(file("a.sav", sha))
        val remote = mapOf("a.sav" to sha.copyOf())

        val cache = SteamAutoCloud.buildRace3CacheEntries(local, remote, keyOf)

        assertEquals(1, cache.size)
        assertArrayEquals("matching sha retained", sha, cache[0].sha)
    }

    @Test
    fun inCloudFailedPut_cacheGetsCloudSha() {
        // local sha differs from cloud's (the PUT never landed) → cache records cloud's old sha so
        // the next launch's diff vs disk re-detects it as modified and retries.
        val localSha = byteArrayOf(7, 7, 7)
        val cloudSha = byteArrayOf(1, 1, 1)
        val local = listOf(file("a.sav", localSha))
        val remote = mapOf("a.sav" to cloudSha)

        val cache = SteamAutoCloud.buildRace3CacheEntries(local, remote, keyOf)

        assertEquals(1, cache.size)
        assertArrayEquals("cache holds cloud's sha, not local's", cloudSha, cache[0].sha)
    }

    @Test
    fun mixedSet_onlyInCloudKept_failedPutsRewritten() {
        val local = listOf(
            file("match.sav", byteArrayOf(5)),
            file("failed.sav", byteArrayOf(7)), // in cloud but sha differs
            file("local_only.sav", byteArrayOf(9)), // not in cloud
        )
        val remote = mapOf(
            "match.sav" to byteArrayOf(5),
            "failed.sav" to byteArrayOf(2),
        )

        val cache = SteamAutoCloud.buildRace3CacheEntries(local, remote, keyOf).associateBy { it.filename }

        assertEquals(2, cache.size)
        assertTrue("local-only excluded", "local_only.sav" !in cache)
        assertArrayEquals(byteArrayOf(5), cache.getValue("match.sav").sha)
        assertArrayEquals("failed PUT rewritten to cloud sha", byteArrayOf(2), cache.getValue("failed.sav").sha)
    }

    @Test
    fun emptyLocal_yieldsEmptyCache() {
        assertTrue(SteamAutoCloud.buildRace3CacheEntries(emptyList(), mapOf("x" to byteArrayOf(1)), keyOf).isEmpty())
    }
}
