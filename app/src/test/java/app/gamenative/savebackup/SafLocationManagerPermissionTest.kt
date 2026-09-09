package app.gamenative.savebackup

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Example tests for [DefaultSafLocationManager] persistable-permission behavior —
 * game-save-backup Task 9.2 (Requirements 10.1, 10.2, 10.3, 10.4).
 *
 * These are plain JUnit 4 example tests. The `suspend` manager methods are driven with
 * [runBlocking]. The manager is exercised against:
 *  - a mocked [Context] whose `contentResolver` is a mocked [ContentResolver], so
 *    `takePersistableUriPermission` and `persistedUriPermissions` are fully controllable;
 *  - an in-memory [FakeRememberedSafLocationStore] so `put`/`remove`/`get` are directly assertable;
 *  - an injectable `treeResolver` lambda that returns a mocked [DocumentFile], so no real
 *    [DocumentFile.fromTreeUri] is needed.
 *
 * ### Why `mockkStatic(Uri::class)` instead of Robolectric
 *
 * `android.net.Uri` is a non-functional stub in a plain JVM unit test, and
 * [DefaultSafLocationManager.rememberedLocation] calls `Uri.parse(remembered)` on the stored
 * string. The design mandates that *property* tests avoid Robolectric; these are *example* tests so
 * Robolectric would be permissible, but statically mocking `Uri.parse` to return a controlled
 * `mockk<Uri>` is cleaner, faster, and matches the Robolectric-free MockK style already used across
 * this test package (e.g. `SteamAutoResolutionUnavailableTest`). Each mocked [Uri] stubs
 * `toString()` because the manager compares grants by URI string. We therefore never rely on real
 * `Uri` behavior.
 */
class SafLocationManagerPermissionTest {

    private val readWriteFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    private lateinit var contentResolver: ContentResolver
    private lateinit var context: Context

    @Before
    fun setUp() {
        // Uri.parse must return a controllable mock: android.net.Uri is a stub off Robolectric.
        mockkStatic(Uri::class)

        contentResolver = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.contentResolver } returns contentResolver
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    // ---- Req 10.1: request on select -------------------------------------

    /**
     * Requirement 10.1: when the user selects an external location, the app requests a persistable
     * permission for it. `tryPersist` must call `takePersistableUriPermission` with the selected
     * URI and the read+write grant flags; when the grant is retained it returns `true` and remembers
     * the URI for the game.
     */
    @Test
    fun tryPersistRequestsPersistableGrantAndRemembersUri() = runBlocking {
        val appId = "STEAM_440"
        val uri = uri("content://tree/primary%3ABackups")
        val store = FakeRememberedSafLocationStore()
        val manager = DefaultSafLocationManager(context, store) { _, _ -> readableDoc() }

        // The grant is retained: a matching read permission is present afterward.
        every { contentResolver.takePersistableUriPermission(uri, readWriteFlags) } returns Unit
        every { contentResolver.persistedUriPermissions } returns listOf(readGrant(uri))

        val result = manager.tryPersist(appId, uri)

        verify(exactly = 1) { contentResolver.takePersistableUriPermission(uri, readWriteFlags) }
        assertTrue("tryPersist should return true when the grant is retained", result)
        assertEquals(
            "the store should remember the persisted URI for the game",
            uri.toString(),
            store.get(appId),
        )
    }

    // ---- Req 10.2: reuse valid grant -------------------------------------

    /**
     * Requirement 10.2: with a previously remembered URI whose persistable grant is still present
     * and whose tree is still resolvable/readable, `rememberedLocation` returns that URI (non-null)
     * so the caller reuses it without re-prompting through the picker.
     */
    @Test
    fun rememberedLocationReusesValidGrantWithoutReprompting() = runBlocking {
        val appId = "GOG_1207658924"
        val uri = uri("content://tree/gog%3ASaves")
        val store = FakeRememberedSafLocationStore().apply { seed(appId, uri.toString()) }
        val manager = DefaultSafLocationManager(context, store) { _, _ -> readableDoc() }

        every { contentResolver.persistedUriPermissions } returns listOf(readGrant(uri))

        val result = manager.rememberedLocation(context, appId)

        assertSame("a valid remembered grant should be reused as-is", uri, result)
        assertEquals(
            "a reused grant must remain remembered",
            uri.toString(),
            store.get(appId),
        )
    }

    // ---- Req 10.3: persist failure continues for one op ------------------

    /**
     * Requirement 10.3: if the persistable grant cannot be obtained (here
     * `takePersistableUriPermission` throws [SecurityException]), `tryPersist` returns `false` and
     * does NOT remember the URI, so the caller warns "folder could not be remembered" and continues
     * using the selection for this operation only. The next operation reopens the picker.
     */
    @Test
    fun tryPersistReturnsFalseAndForgetsWhenGrantThrows() = runBlocking {
        val appId = "EPIC_fortnite"
        val uri = uri("content://tree/epic%3ASaves")
        val store = FakeRememberedSafLocationStore()
        val manager = DefaultSafLocationManager(context, store) { _, _ -> readableDoc() }

        every {
            contentResolver.takePersistableUriPermission(uri, readWriteFlags)
        } throws SecurityException("no persistable grant")

        val result = manager.tryPersist(appId, uri)

        assertFalse("tryPersist should return false when the grant cannot be obtained", result)
        assertNull("a failed persist must not remember the URI", store.get(appId))
    }

    /**
     * Requirement 10.3 (retention variant): the grant call succeeds but the grant is not actually
     * retained (`persistedUriPermissions` does not contain it). `tryPersist` must still return
     * `false` and remember nothing, because a provider may silently drop the grant.
     */
    @Test
    fun tryPersistReturnsFalseWhenGrantNotRetained() = runBlocking {
        val appId = "AMAZON_123"
        val uri = uri("content://tree/amazon%3ASaves")
        val store = FakeRememberedSafLocationStore()
        val manager = DefaultSafLocationManager(context, store) { _, _ -> readableDoc() }

        every { contentResolver.takePersistableUriPermission(uri, readWriteFlags) } returns Unit
        // Grant was not retained: the persisted list is empty afterward.
        every { contentResolver.persistedUriPermissions } returns emptyList()

        val result = manager.tryPersist(appId, uri)

        assertFalse("tryPersist should return false when the grant is not retained", result)
        assertNull("an unretained persist must not remember the URI", store.get(appId))
    }

    // ---- Req 10.4: revoked/expired/unresolvable grant reopens picker -----

    /**
     * Requirement 10.4 (a): a remembered URI whose persistable grant is no longer present (revoked
     * or expired — it disappears from `persistedUriPermissions`) resolves to `null` and the stale
     * entry is forgotten. A `null` return is how the caller knows to reopen the picker.
     */
    @Test
    fun rememberedLocationReopensPickerWhenGrantRevoked() = runBlocking {
        val appId = "STEAM_620"
        val uri = uri("content://tree/revoked%3ASaves")
        val store = FakeRememberedSafLocationStore().apply { seed(appId, uri.toString()) }
        val manager = DefaultSafLocationManager(context, store) { _, _ -> readableDoc() }

        // The grant is gone from the persisted list (revoked/expired).
        every { contentResolver.persistedUriPermissions } returns emptyList()

        val result = manager.rememberedLocation(context, appId)

        assertNull("a revoked/expired grant must resolve to null so the picker reopens", result)
        assertNull("the stale entry must be forgotten", store.get(appId))
    }

    /**
     * Requirement 10.4 (b): a remembered URI whose grant is still present but whose tree can no
     * longer be resolved/read (here `treeResolver` returns `null`) resolves to `null` and forgets
     * the entry, so the caller reopens the picker.
     */
    @Test
    fun rememberedLocationReopensPickerWhenTreeUnresolvable() = runBlocking {
        val appId = "GOG_555"
        val uri = uri("content://tree/gone%3ASaves")
        val store = FakeRememberedSafLocationStore().apply { seed(appId, uri.toString()) }
        // Provider uninstalled / folder removed => tree resolver yields null.
        val manager = DefaultSafLocationManager(context, store) { _, _ -> null }

        every { contentResolver.persistedUriPermissions } returns listOf(readGrant(uri))

        val result = manager.rememberedLocation(context, appId)

        assertNull("an unresolvable tree must resolve to null so the picker reopens", result)
        assertNull("the entry for an unresolvable tree must be forgotten", store.get(appId))
    }

    /**
     * Requirement 10.4 (b, unreadable variant): the grant is present and the tree resolves, but the
     * resolved [DocumentFile] reports it does not exist / cannot be read. `rememberedLocation` must
     * resolve to `null` and forget the entry.
     */
    @Test
    fun rememberedLocationReopensPickerWhenTreeUnreadable() = runBlocking {
        val appId = "EPIC_777"
        val uri = uri("content://tree/unreadable%3ASaves")
        val store = FakeRememberedSafLocationStore().apply { seed(appId, uri.toString()) }
        val unreadable = mockk<DocumentFile> {
            every { exists() } returns true
            every { canRead() } returns false
        }
        val manager = DefaultSafLocationManager(context, store) { _, _ -> unreadable }

        every { contentResolver.persistedUriPermissions } returns listOf(readGrant(uri))

        val result = manager.rememberedLocation(context, appId)

        assertNull("an unreadable tree must resolve to null so the picker reopens", result)
        assertNull("the entry for an unreadable tree must be forgotten", store.get(appId))
    }

    // ---- helpers ---------------------------------------------------------

    /** Build a mocked [Uri] that returns [value] from `toString()` and is returned by `Uri.parse`. */
    private fun uri(value: String): Uri {
        val u = mockk<Uri>()
        every { u.toString() } returns value
        every { Uri.parse(value) } returns u
        return u
    }

    /** A [UriPermission] mock reporting a read grant for [uri]. */
    private fun readGrant(uri: Uri): UriPermission = mockk {
        every { this@mockk.uri } returns uri
        every { isReadPermission } returns true
    }

    /** A [DocumentFile] mock that exists and is readable (a valid, reusable tree). */
    private fun readableDoc(): DocumentFile = mockk {
        every { exists() } returns true
        every { canRead() } returns true
    }

    /**
     * In-memory [RememberedSafLocationStore] fake so `put`/`remove` are directly observable via
     * [get]. [seed] pre-populates an entry without going through the manager.
     */
    private class FakeRememberedSafLocationStore : RememberedSafLocationStore {
        private val map = mutableMapOf<String, String>()

        fun seed(appId: String, treeUri: String) {
            map[appId] = treeUri
        }

        override suspend fun get(appId: String): String? = map[appId]

        override suspend fun put(appId: String, treeUri: String) {
            map[appId] = treeUri
        }

        override suspend fun remove(appId: String) {
            map.remove(appId)
        }
    }
}
