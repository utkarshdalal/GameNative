package app.gamenative.mods

import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallSource
import java.io.IOException
import java.util.zip.ZipException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModImporterTest {
    @Test
    fun suggestedModName_handlesEachLocalSourceType() {
        assertEquals(
            "SkyUI 5.2",
            LocalModImporter.suggestedModName(
                "SkyUI 5.2.zip",
                LocalModSourceType.ARCHIVE,
                1,
                "Local mod",
            ),
        )
        assertEquals(
            "mod.backup",
            LocalModImporter.suggestedModName(
                "mod.backup",
                LocalModSourceType.ARCHIVE,
                1,
                "Local mod",
            ),
        )
        assertEquals(
            "settings",
            LocalModImporter.suggestedModName(
                "settings.ini",
                LocalModSourceType.FILES,
                1,
                "Local mod",
            ),
        )
        assertEquals(
            "Local mod",
            LocalModImporter.suggestedModName(
                "3 selected files",
                LocalModSourceType.FILES,
                3,
                "Local mod",
            ),
        )
        assertEquals(
            "Better Textures",
            LocalModImporter.suggestedModName(
                "Better Textures",
                LocalModSourceType.FOLDER,
                20,
                "Local mod",
            ),
        )
    }

    @Test
    fun supportedArchiveLabel_onlyReportsKnownFormats() {
        assertEquals("ZIP", LocalModImporter.supportedArchiveLabel("mod.ZIP"))
        assertEquals("", LocalModImporter.supportedArchiveLabel("mod.backup"))
        assertEquals("", LocalModImporter.supportedArchiveLabel("mod"))
    }

    @Test
    fun truncateAtCodePointBoundary_keepsSurrogatePairsIntact() {
        val emoji = "\uD83D\uDE00"

        assertEquals("aaaa", "aaaa${emoji}x".truncateAtCodePointBoundary(5))
        assertEquals("aaa$emoji", "aaa${emoji}x".truncateAtCodePointBoundary(5))
        assertEquals("abcde", "abcdef".truncateAtCodePointBoundary(5))
    }

    @Test
    fun normalizeRelativePath_preservesSafeFolderStructure() {
        assertEquals(
            "Data/SKSE/Plugins/settings.ini",
            LocalModImporter.normalizeRelativePath(
                listOf("Data", "SKSE", "Plugins", "settings.ini"),
            ),
        )
    }

    @Test
    fun normalizeRelativePath_rejectsTraversalAndWindowsInvalidNames() {
        assertThrows(IOException::class.java) {
            LocalModImporter.normalizeRelativePath(listOf("..", "settings.ini"))
        }
        assertThrows(IOException::class.java) {
            LocalModImporter.normalizeRelativePath(listOf("Data", "bad:name.ini"))
        }
        assertThrows(IOException::class.java) {
            LocalModImporter.normalizeRelativePath(listOf("Data", "trailing."))
        }
        assertThrows(IOException::class.java) {
            LocalModImporter.normalizeRelativePath(listOf("Data", "CON.txt"))
        }
        assertThrows(IOException::class.java) {
            LocalModImporter.normalizeRelativePath(listOf("Data", "lpt1"))
        }
    }

    @Test
    fun normalizeRelativePath_rejectsPathsBeyondImportLimit() {
        assertThrows(IOException::class.java) {
            LocalModImporter.normalizeRelativePath(
                List(5) { "x".repeat(220) } + "file.ini",
            )
        }
    }

    @Test
    fun validateUniquePaths_rejectsCaseInsensitiveCollisions() {
        assertThrows(IOException::class.java) {
            LocalModImporter.validateUniquePaths(
                listOf("Data/config.ini", "data/CONFIG.ini"),
            )
        }
    }

    @Test
    fun localSources_haveNoFabricatedNexusIdentity() {
        LocalModSourceType.entries.forEach { localSource ->
            val install = ModInstall(
                installId = "local_${localSource.name}",
                appId = "game",
                source = localSource.installSource.name,
                modName = "Local mod",
                fileName = "local content",
                archivePath = "",
                extractedPath = "/mods/local-id",
                archiveSha256 = "abc123",
            )

            assertNull(install.nexusGameDomain)
            assertNull(install.nexusModId)
            assertNull(install.nexusFileId)
            assertEquals("abc123", install.archiveSha256)
            assertTrue(localSource == LocalModSourceType.fromInstallSource(install.source))
        }
        assertNull(LocalModSourceType.fromInstallSource(ModInstallSource.NEXUS.name))
        assertNull(LocalModSourceType.fromInstallSource("FUTURE_REMOTE_SOURCE"))
    }

    @Test
    fun invalidArchiveClassifier_walksWrappedCausesWithoutDiscardingStorageFailures() {
        assertTrue(
            LocalModImporter.isInvalidArchiveError(
                IOException("Import failed", ZipException("zip END header not found")),
            ),
        )
        assertTrue(
            LocalModImporter.isInvalidArchiveError(
                IOException("Archive expands beyond the safety limit"),
            ),
        )
        assertEquals(
            false,
            LocalModImporter.isInvalidArchiveError(IOException("No space left on device")),
        )
    }

    @Test
    fun requestMetadata_rejectsOversizedBinderFields() {
        val request = LocalModImportRequest(
            installId = "local_test",
            appId = "STEAM_1",
            sourceType = LocalModSourceType.FILES,
            modName = "m".repeat(LocalModImporter.MAX_MOD_NAME_LENGTH + 1),
            sourceName = "settings.ini",
        )

        assertEquals(false, LocalModImporter.hasValidRequestMetadata(request))
    }

    @Test
    fun requestMetadata_rejectsReservedCachePathComponents() {
        val request = LocalModImportRequest(
            installId = "local_test",
            appId = "game",
            sourceType = LocalModSourceType.FILES,
            modName = "Local files",
            sourceName = "settings.ini",
        )

        assertEquals(
            false,
            LocalModImporter.hasValidRequestMetadata(request.copy(appId = ".")),
        )
        assertEquals(
            false,
            LocalModImporter.hasValidRequestMetadata(request.copy(appId = "..")),
        )
        assertEquals(
            false,
            LocalModImporter.hasValidRequestMetadata(
                request.copy(installId = "local_test.tmp"),
            ),
        )
        assertEquals(
            false,
            LocalModImporter.hasValidRequestMetadata(
                request.copy(installId = "local_test.previous"),
            ),
        )
    }

    @Test
    fun retryTarget_mustBelongToTheSameAppAndLocalSourceType() {
        val request = LocalModImportRequest(
            installId = "local_test",
            appId = "game-one",
            sourceType = LocalModSourceType.FILES,
            modName = "Local files",
            sourceName = "settings.ini",
        )
        val existing = ModInstall(
            installId = request.installId,
            appId = request.appId,
            source = request.sourceType.installSource.name,
            modName = request.modName,
            fileName = request.sourceName,
            archivePath = "",
            extractedPath = "/mods/local-test",
        )

        assertTrue(LocalModImporter.isCompatibleExistingInstall(existing, request))
        assertEquals(
            false,
            LocalModImporter.isCompatibleExistingInstall(
                existing.copy(appId = "game-two"),
                request,
            ),
        )
        assertEquals(
            false,
            LocalModImporter.isCompatibleExistingInstall(
                existing.copy(source = ModInstallSource.LOCAL_FOLDER.name),
                request,
            ),
        )
    }
}
