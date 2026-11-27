package app.gamenative.html5.host

import app.gamenative.html5.asar.ProtocolServedPage
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class Html5DefaultProfilePagesTest {

    private lateinit var tmpRoot: File

    @Before
    fun setUp() {
        tmpRoot = Files.createTempDirectory("default-profile-pages-test-").toFile()
    }

    @After
    fun tearDown() {
        tmpRoot.deleteRecursively()
    }

    @Test
    fun recordsRoundTripAndAnEmptyFileIsDeleted() {
        val file = File(tmpRoot, "html5/default-profile-pages")
        PageRecords.put(file, "STEAM_1", setOf("example.test", "cdn.example.test"))
        PageRecords.put(file, "STEAM_2", setOf("other.test"))
        assertEquals(
            mapOf("STEAM_1" to setOf("example.test", "cdn.example.test"), "STEAM_2" to setOf("other.test")),
            PageRecords.read(file),
        )
        PageRecords.put(file, "STEAM_1", setOf("example.test"))
        assertEquals(setOf("example.test"), PageRecords.read(file)["STEAM_1"])
        PageRecords.remove(file, "STEAM_1")
        PageRecords.remove(file, "STEAM_2")
        assertFalse(file.exists())
    }

    // uninstall leaves a host another installed container also ran at.
    @Test
    fun ownHostsLeaveOutHostsSharedWithOtherContainers() {
        val records = mapOf(
            "STEAM_1" to setOf("example.test", "cdn.shared.test"),
            "GOG_1" to setOf("cdn.shared.test"),
            "STEAM_9" to setOf("example.test"),
        )
        val installed = setOf("GOG_1")
        assertEquals(
            setOf("example.test"),
            PageRecords.ownHosts(records, "STEAM_1") { it != "STEAM_1" && it in installed },
        )
        assertEquals(
            setOf("example.test", "cdn.shared.test"),
            PageRecords.ownHosts(records, "STEAM_1") { false },
        )
        assertEquals(emptySet<String>(), PageRecords.ownHosts(records, "STEAM_404") { false })
    }

    @Test
    fun signInHostsAreNeverRecorded() {
        val page = ProtocolServedPage(
            host = "Example.test",
            aliasHosts = setOf("store.steampowered.com", "cdn.example.test"),
            prefix = "/p/",
            entryName = "index.html",
            entryHtml = null,
            resourceDir = "site",
            userAgentSuffix = null,
        )
        assertEquals(setOf("cdn.example.test", "example.test"), Html5DefaultProfilePages.hostsOf(page))
        assertEquals("https://example.test", Html5DefaultProfilePages.originOf("example.test"))
    }
}
