package app.gamenative.html5.savesync

import app.gamenative.html5.savesync.SaveDirectoryResolver.SavePathPair
import app.gamenative.html5.savesync.SaveDirectoryResolver.WebViewPaths
import app.gamenative.html5.savesync.SaveDirectoryResolver.WinePaths
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SaveSyncStrategyLevelDbTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // an fs-canonical title leaves the webview IDB dir as an uncommitted shell: the IDB rewrite is skipped and
    // the Wine leveldb kept, so its blob files must be kept too.
    @Test
    fun outbound_emptyShellWebViewIdb_keepsWineBlobs() {
        val root = tmp.root
        val webIdb = File(root, "web-idb").apply { mkdirs() }
        File(webIdb, "LOG").writeText("")
        val wineIdb = File(root, "wine-idb")
        FixtureBuilder.idbWithDatabaseName(wineIdb, "file__0", "GameDB")
        val wineBlobDir = File(root, "wine-blob")
        val blob = File(wineBlobDir, "1/00/3").apply {
            parentFile?.mkdirs()
            writeText("blob")
        }
        val paths = SavePathPair(
            webView = WebViewPaths(File(root, "web-ls"), webIdb, File(root, "web-blob")),
            wine = WinePaths(root, File(root, "wine-ls"), wineIdb, wineBlobDir),
            syncMode = SyncMode.CLOUD_ENABLED,
        )
        val origins = Origins("http://steam-1.localhost:59099", "http_steam-1.localhost_59099", "file://", "file__0")

        SaveSyncStrategy.LevelDbOriginRewrite.syncOutbound(paths, origins)

        assertTrue("wine blob must survive", blob.isFile)
        assertEquals("blob", blob.readText())
    }

    // chromium holds the WebView's LS leveldb open for the whole process; the page restores localStorage instead.
    @Test
    fun inbound_leavesWebViewLocalStorageAlone() {
        val root = tmp.root
        val wineLs = File(root, "wine-ls")
        FixtureBuilder.lsWithOrigins(wineLs, "file://" to mapOf("k" to "v".toByteArray()))
        val webLs = File(root, "web-ls")
        val paths = SavePathPair(
            webView = WebViewPaths(webLs, File(root, "web-idb"), File(root, "web-blob")),
            wine = WinePaths(root, wineLs, File(root, "wine-idb"), File(root, "wine-blob")),
            syncMode = SyncMode.CLOUD_ENABLED,
        )
        val origins = Origins("http://steam-1.localhost:59099", "http_steam-1.localhost_59099", "file://", "file__0")

        SaveSyncStrategy.LevelDbOriginRewrite.syncInbound(paths, origins)

        assertTrue("webview LS must not be written", !webLs.exists())
    }
}
