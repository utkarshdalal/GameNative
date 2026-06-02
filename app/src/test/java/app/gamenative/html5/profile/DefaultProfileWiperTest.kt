package app.gamenative.html5.profile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// 2 — I5 (scope: Local Storage + IndexedDB only) + I6 (idempotency via flag).
// object-direct coverage — lambda-backed flag state; zero application-class dependency.
@RunWith(RobolectricTestRunner::class)
class DefaultProfileWiperTest {

    private lateinit var ctx: Context
    private lateinit var defaultDir: File
    private var flagState: Boolean = false

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        defaultDir = File(ctx.dataDir, "app_webview/Default")
        if (defaultDir.exists()) defaultDir.deleteRecursively()
        flagState = false
    }

    @After
    fun tearDown() {
        if (defaultDir.exists()) defaultDir.deleteRecursively()
    }

    @Test
    fun first_boot_wipes_only_localstorage_and_indexeddb() {
        val subdirs = listOf("Local Storage", "IndexedDB", "Cookies", "Cache", "Service Worker")
        val markers = subdirs.associateWith { sub ->
            File(defaultDir, "$sub/marker.bin").apply {
                parentFile?.mkdirs()
                writeText("seed")
            }
        }

        val ran = DefaultProfileWiper.wipeIfNeeded(
            context = ctx,
            flagRead = { flagState },
            flagWrite = { flagState = it },
        )

        assertTrue("first boot should report wipe completed", ran)
        markers.forEach { (sub, f) ->
            if (sub == "Local Storage" || sub == "IndexedDB") {
                assertFalse("$sub marker should be wiped", f.exists())
            } else {
                assertTrue("$sub marker should be preserved", f.exists())
            }
        }
        assertTrue("flag should flip true on success", flagState)
    }

    @Test
    fun second_boot_is_no_op_when_flag_already_true() {
        flagState = true
        val f = File(defaultDir, "Local Storage/foo.ldb").apply {
            parentFile?.mkdirs()
            writeText("should remain")
        }

        val ran = DefaultProfileWiper.wipeIfNeeded(
            context = ctx,
            flagRead = { flagState },
            flagWrite = { flagState = it },
        )

        assertFalse("short-circuit returns false — no wipe performed", ran)
        assertTrue("second-boot no-op must not touch files", f.exists())
        assertEquals("should remain", f.readText())
    }

    @Test
    fun fresh_install_no_default_dir_flips_flag_without_crash() {
        // no seed — defaultDir does not exist
        val ran = DefaultProfileWiper.wipeIfNeeded(
            context = ctx,
            flagRead = { flagState },
            flagWrite = { flagState = it },
        )

        assertTrue("fresh install should still report completion", ran)
        assertTrue("flag flips true on both-subtree-missing success path", flagState)
    }
}
