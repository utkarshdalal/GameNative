package app.gamenative.savebackup

import android.content.Context
import android.net.Uri
import android.os.Environment
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.enums.PathType
import app.gamenative.savebackup.SaveBackupOrchestrator.ExportPickers
import app.gamenative.savebackup.SaveBackupOrchestrator.ImportPickers
import com.winlator.container.Container
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Example tests for [SaveBackupOrchestrator] two-picker sequencing, cancellation, and atomicity —
 * game-save-backup Task 11.3 (Requirements 4.1, 4.2, 4.2a, 4.6, 5.1, 5.1a, 5.2, 5.3, 5.7, 5.8, 6.5,
 * 13.4).
 *
 * These are plain JUnit 4 example tests (no Robolectric). The orchestrator is driven with fakes and
 * mocks:
 *  - a **mocked** [SaveBackupEngine] whose `export`/`import` default to [BackupResult.Success]; the
 *    key atomicity assertion is `coVerify(exactly = 0)` on abort/cancel paths — the engine is the
 *    only writer, so if it is never called no external/container change can have occurred.
 *  - a **mocked** [SaveLocationResolutionService] whose `resolve(...)` returns a configurable
 *    [ResolveOutcome] (`Resolved` / `NeedsBrowser` / `Unresolved`).
 *  - a **mocked** [SafLocationManager] whose `rememberedLocation` returns a configurable `Uri?`
 *    (null forces the picker) and whose `tryPersist` returns a configurable Boolean.
 *  - an injected `resolveContainer` lambda that returns a mocked [Container] or throws (to test the
 *    container-unresolvable path).
 *  - fake [ExportPickers]/[ImportPickers] (anonymous objects) that append each call to a shared
 *    ordered list so sequencing (browser-then-SAF for export; SAF-then-browser for import) is
 *    directly assertable, and that can be configured to return values or throw
 *    [PickerOpenException].
 *
 * [Uri] and [Container] are opaque Android types here, mocked with `mockk<...>()`; [LibraryItem] is
 * a real data class constructed directly (its `gameId` derives from the source-prefixed `appId`).
 * All `suspend` entry points are driven with [runBlocking].
 */
class SaveBackupOrchestratorSequencingTest {

    private lateinit var engine: SaveBackupEngine
    private lateinit var resolutionService: SaveLocationResolutionService
    private lateinit var safLocationManager: SafLocationManager
    private lateinit var context: Context
    private lateinit var container: Container

    /** A real LibraryItem; gameId derives from "STEAM_440" → 440. */
    private val item = LibraryItem(appId = "STEAM_440", name = "Team Fortress 2", gameSource = GameSource.STEAM)

    /** Ordered record of picker + engine calls, used for sequencing assertions. */
    private val callOrder = mutableListOf<String>()

    @Before
    fun setUp() {
        // Referencing/instantiating com.winlator.container.Container triggers its static
        // initializer, which calls Environment.getExternalStoragePublicDirectory — an unmocked
        // Android stub in a plain (Robolectric-free) JVM unit test. Stub it statically first.
        mockkStatic(Environment::class)
        every { Environment.getExternalStoragePublicDirectory(any()) } returns File("/sdcard/Download")

        engine = mockk()
        resolutionService = mockk()
        safLocationManager = mockk()
        context = mockk(relaxed = true)
        container = mockk(relaxed = true)
        callOrder.clear()

        // Default: engine reports Success. Individual tests that reach the engine rely on this;
        // abort/cancel tests assert the engine is NEVER called (coVerify exactly = 0).
        coEvery { engine.export(any(), any(), any(), any(), any()) } coAnswers {
            callOrder += "engine.export"
            BackupResult.Success
        }
        coEvery { engine.import(any(), any(), any(), any()) } coAnswers {
            callOrder += "engine.import"
            BackupResult.Success
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(Environment::class)
    }

    private fun orchestrator(
        resolveContainer: (Context, String) -> Container = { _, _ -> container },
    ) = SaveBackupOrchestrator(engine, resolutionService, safLocationManager, resolveContainer)

    private fun saveLocation(): SaveLocation = SaveLocation(PathType.WinSavedGames, "MyGame/Slot1")

    private fun resolvedOutcome(): ResolveOutcome =
        ResolveOutcome.Resolved(Paths.get("/tmp/container/saves"), saveLocation())

    // ========================================================================
    // EXPORT
    // ========================================================================

    /**
     * Req 4.1: Export with an UNSET save location opens the container browser FIRST, then the SAF
     * picker. resolve → NeedsBrowser; browser returns a SaveLocation; rememberedLocation null so the
     * SAF picker runs. Asserts the call order [openContainerBrowser, pickExternalTree] and that the
     * engine exported exactly once → Success.
     */
    @Test
    fun exportUnsetLocationOpensBrowserThenSaf() = runBlocking {
        coEvery { resolutionService.resolve(any(), any(), any<LibraryItem>()) } returns
            ResolveOutcome.NeedsBrowser(automaticUnavailable = false)
        coEvery { safLocationManager.rememberedLocation(any(), any()) } returns null
        coEvery { safLocationManager.tryPersist(any(), any()) } returns true

        val chosen = saveLocation()
        val tree = mockk<Uri>()
        val pickers = object : ExportPickers {
            override suspend fun openContainerBrowser(container: Container, appId: String, gameId: Int): SaveLocation? {
                callOrder += "openContainerBrowser"
                return chosen
            }
            override suspend fun pickExternalTree(): Uri? {
                callOrder += "pickExternalTree"
                return tree
            }
        }

        val outcome = orchestrator().export(context, item, ExportLayout.ARCHIVE, pickers)

        assertEquals(ExportOutcome.Success, outcome)
        assertEquals(listOf("openContainerBrowser", "pickExternalTree", "engine.export"), callOrder)
        coVerify(exactly = 1) { engine.export(context, item, tree, ExportLayout.ARCHIVE, chosen) }
    }

    /**
     * Req 4.2: Export with a KNOWN save location goes straight to the SAF picker WITHOUT opening the
     * container browser. resolve → Resolved. Asserts openContainerBrowser was NOT called,
     * pickExternalTree WAS, and the engine exported → Success.
     */
    @Test
    fun exportKnownLocationOpensSafOnlyNoBrowser() = runBlocking {
        coEvery { resolutionService.resolve(any(), any(), any<LibraryItem>()) } returns resolvedOutcome()
        coEvery { safLocationManager.rememberedLocation(any(), any()) } returns null
        coEvery { safLocationManager.tryPersist(any(), any()) } returns true

        val tree = mockk<Uri>()
        val pickers = object : ExportPickers {
            override suspend fun openContainerBrowser(container: Container, appId: String, gameId: Int): SaveLocation? {
                callOrder += "openContainerBrowser"
                return saveLocation()
            }
            override suspend fun pickExternalTree(): Uri? {
                callOrder += "pickExternalTree"
                return tree
            }
        }

        val outcome = orchestrator().export(context, item, ExportLayout.ARCHIVE, pickers)

        assertEquals(ExportOutcome.Success, outcome)
        assertEquals(listOf("pickExternalTree", "engine.export"), callOrder)
        assertTrue("container browser must not be opened for a known location", "openContainerBrowser" !in callOrder)
        coVerify(exactly = 1) { engine.export(any(), any(), any(), any(), any()) }
    }

    /**
     * Req 4.2a (browser open failure): the container browser throwing [PickerOpenException] aborts
     * the export → [ExportOutcome.Aborted] and the engine is NEVER called (no external changes).
     */
    @Test
    fun exportAbortsWhenContainerBrowserFailsToOpen() = runBlocking {
        coEvery { resolutionService.resolve(any(), any(), any<LibraryItem>()) } returns
            ResolveOutcome.NeedsBrowser(automaticUnavailable = false)
        coEvery { safLocationManager.rememberedLocation(any(), any()) } returns null

        val pickers = object : ExportPickers {
            override suspend fun openContainerBrowser(container: Container, appId: String, gameId: Int): SaveLocation? {
                callOrder += "openContainerBrowser"
                throw PickerOpenException(PickerOpenException.Picker.CONTAINER_BROWSER)
            }
            override suspend fun pickExternalTree(): Uri? {
                callOrder += "pickExternalTree"
                return mockk()
            }
        }

        val outcome = orchestrator().export(context, item, ExportLayout.ARCHIVE, pickers)

        assertTrue("browser open failure must abort the export, got $outcome", outcome is ExportOutcome.Aborted)
        assertTrue("SAF picker must not run after browser open failure", "pickExternalTree" !in callOrder)
        coVerify(exactly = 0) { engine.export(any(), any(), any(), any(), any()) }
    }

    /**
     * Req 4.2a (SAF open failure): the SAF picker throwing [PickerOpenException] aborts the export →
     * [ExportOutcome.Aborted] and the engine is NEVER called (no external changes).
     */
    @Test
    fun exportAbortsWhenSafPickerFailsToOpen() = runBlocking {
        coEvery { resolutionService.resolve(any(), any(), any<LibraryItem>()) } returns resolvedOutcome()
        coEvery { safLocationManager.rememberedLocation(any(), any()) } returns null

        val pickers = object : ExportPickers {
            override suspend fun openContainerBrowser(container: Container, appId: String, gameId: Int): SaveLocation? {
                callOrder += "openContainerBrowser"
                return saveLocation()
            }
            override suspend fun pickExternalTree(): Uri? {
                callOrder += "pickExternalTree"
                throw PickerOpenException(PickerOpenException.Picker.SAF_PICKER)
            }
        }

        val outcome = orchestrator().export(context, item, ExportLayout.ARCHIVE, pickers)

        assertTrue("SAF open failure must abort the export, got $outcome", outcome is ExportOutcome.Aborted)
        coVerify(exactly = 0) { engine.export(any(), any(), any(), any(), any()) }
    }

    /**
     * Req 4.6, 13.4 (browser cancel): the user cancelling the container browser (null return) yields
     * [ExportOutcome.Cancelled] — never success or failure — and the engine is NEVER called.
     */
    @Test
    fun exportBrowserCancelYieldsCancelledNeverSuccessOrFailure() = runBlocking {
        coEvery { resolutionService.resolve(any(), any(), any<LibraryItem>()) } returns
            ResolveOutcome.NeedsBrowser(automaticUnavailable = false)
        coEvery { safLocationManager.rememberedLocation(any(), any()) } returns null

        val pickers = object : ExportPickers {
            override suspend fun openContainerBrowser(container: Container, appId: String, gameId: Int): SaveLocation? {
                callOrder += "openContainerBrowser"
                return null // cancel
            }
            override suspend fun pickExternalTree(): Uri? {
                callOrder += "pickExternalTree"
                return mockk()
            }
        }

        val outcome = orchestrator().export(context, item, ExportLayout.ARCHIVE, pickers)

        assertEquals(ExportOutcome.Cancelled, outcome)
        assertTrue("SAF picker must not run after browser cancel", "pickExternalTree" !in callOrder)
        coVerify(exactly = 0) { engine.export(any(), any(), any(), any(), any()) }
    }

    /**
     * Req 4.6, 13.4 (SAF cancel): the user cancelling the SAF picker (null return) yields
     * [ExportOutcome.Cancelled] — never success or failure — and the engine is NEVER called.
     */
    @Test
    fun exportSafCancelYieldsCancelledNeverSuccessOrFailure() = runBlocking {
        coEvery { resolutionService.resolve(any(), any(), any<LibraryItem>()) } returns resolvedOutcome()
        coEvery { safLocationManager.rememberedLocation(any(), any()) } returns null

        val pickers = object : ExportPickers {
            override suspend fun openContainerBrowser(container: Container, appId: String, gameId: Int): SaveLocation? {
                callOrder += "openContainerBrowser"
                return saveLocation()
            }
            override suspend fun pickExternalTree(): Uri? {
                callOrder += "pickExternalTree"
                return null // cancel
            }
        }

        val outcome = orchestrator().export(context, item, ExportLayout.ARCHIVE, pickers)

        assertEquals(ExportOutcome.Cancelled, outcome)
        coVerify(exactly = 0) { engine.export(any(), any(), any(), any(), any()) }
    }

    /**
     * Req 6.5 (container unresolvable): resolveContainer throwing aborts the export →
     * [ExportOutcome.Aborted], and resolution, pickers, and engine are NEVER invoked.
     */
    @Test
    fun exportAbortsWhenContainerUnresolvable() = runBlocking {
        val pickers = object : ExportPickers {
            override suspend fun openContainerBrowser(container: Container, appId: String, gameId: Int): SaveLocation? {
                callOrder += "openContainerBrowser"
                return saveLocation()
            }
            override suspend fun pickExternalTree(): Uri? {
                callOrder += "pickExternalTree"
                return mockk()
            }
        }

        val outcome = orchestrator(resolveContainer = { _, _ ->
            throw IllegalStateException("no container")
        }).export(context, item, ExportLayout.ARCHIVE, pickers)

        assertTrue("unresolvable container must abort the export, got $outcome", outcome is ExportOutcome.Aborted)
        assertTrue("no pickers should run when the container is unresolvable", callOrder.isEmpty())
        coVerify(exactly = 0) { resolutionService.resolve(any(), any(), any<LibraryItem>()) }
        coVerify(exactly = 0) { engine.export(any(), any(), any(), any(), any()) }
    }

    /**
     * Req 10.2 (remembered grant reuse): for a RAW_TREE export (an OpenDocumentTree grant), a
     * still-valid remembered grant is reused WITHOUT opening the SAF picker; the engine exports
     * with that remembered Uri → Success.
     */
    @Test
    fun exportRawTreeReusesRememberedGrantWithoutSafPicker() = runBlocking {
        coEvery { resolutionService.resolve(any(), any(), any<LibraryItem>()) } returns resolvedOutcome()
        val remembered = mockk<Uri>()
        coEvery { safLocationManager.rememberedLocation(any(), any()) } returns remembered

        val pickers = object : ExportPickers {
            override suspend fun openContainerBrowser(container: Container, appId: String, gameId: Int): SaveLocation? {
                callOrder += "openContainerBrowser"
                return saveLocation()
            }
            override suspend fun pickExternalTree(): Uri? {
                callOrder += "pickExternalTree"
                return mockk()
            }
        }

        val outcome = orchestrator().export(context, item, ExportLayout.RAW_TREE, pickers)

        assertEquals(ExportOutcome.Success, outcome)
        assertTrue("SAF picker must not run when a remembered grant exists", "pickExternalTree" !in callOrder)
        coVerify(exactly = 1) { engine.export(context, item, remembered, ExportLayout.RAW_TREE, any()) }
    }

    /**
     * Archive export uses CreateDocument (a document URI, not a persistable tree grant), so it must
     * NOT reuse a remembered grant and must NOT persist one — it always prompts via the picker.
     */
    @Test
    fun exportArchiveDoesNotReuseOrPersistRememberedGrant() = runBlocking {
        coEvery { resolutionService.resolve(any(), any(), any<LibraryItem>()) } returns resolvedOutcome()
        // Even if a remembered grant exists, archive export must ignore it.
        coEvery { safLocationManager.rememberedLocation(any(), any()) } returns mockk<Uri>()

        val chosen = mockk<Uri>()
        val pickers = object : ExportPickers {
            override suspend fun openContainerBrowser(container: Container, appId: String, gameId: Int): SaveLocation? {
                callOrder += "openContainerBrowser"
                return saveLocation()
            }
            override suspend fun pickExternalTree(): Uri? {
                callOrder += "pickExternalTree"
                return chosen
            }
        }

        val outcome = orchestrator().export(context, item, ExportLayout.ARCHIVE, pickers)

        assertEquals(ExportOutcome.Success, outcome)
        assertTrue("archive export must open the document picker", "pickExternalTree" in callOrder)
        // The picked document URI is used as-is; it is never persisted as a tree grant.
        coVerify(exactly = 1) { engine.export(context, item, chosen, ExportLayout.ARCHIVE, any()) }
        coVerify(exactly = 0) { safLocationManager.tryPersist(any(), any()) }
    }

    // ========================================================================
    // IMPORT
    // ========================================================================

    /**
     * Req 5.1, 5.2: Import with an UNSET destination opens the SAF source picker FIRST, then the
     * container browser. rememberedLocation null → pickImportSource returns a Uri; resolve →
     * NeedsBrowser → openContainerBrowser returns a SaveLocation. Asserts the call order
     * [pickImportSource, openContainerBrowser] (SAF FIRST) and that the engine imported → Success.
     */
    @Test
    fun importUnsetLocationOpensSafThenBrowser() = runBlocking {
        coEvery { safLocationManager.rememberedLocation(any(), any()) } returns null
        coEvery { safLocationManager.tryPersist(any(), any()) } returns true
        coEvery { resolutionService.resolve(any(), any(), any<LibraryItem>()) } returns
            ResolveOutcome.NeedsBrowser(automaticUnavailable = false)

        val source = mockk<Uri>()
        val chosen = saveLocation()
        val pickers = object : ImportPickers {
            override suspend fun pickImportSource(): Uri? {
                callOrder += "pickImportSource"
                return source
            }
            override suspend fun openContainerBrowser(container: Container, appId: String, gameId: Int): SaveLocation? {
                callOrder += "openContainerBrowser"
                return chosen
            }
        }

        val outcome = orchestrator().import(context, item, pickers)

        assertEquals(ImportOutcome.Success, outcome)
        assertEquals(listOf("pickImportSource", "openContainerBrowser", "engine.import"), callOrder)
        coVerify(exactly = 1) { engine.import(context, item, source, chosen) }
    }

    /**
     * Req 5.3: Import with a KNOWN destination imports directly WITHOUT opening the container
     * browser. pickImportSource returns a Uri; resolve → Resolved. Asserts openContainerBrowser was
     * NOT called and the engine imported → Success.
     */
    @Test
    fun importKnownLocationImportsDirectlyNoBrowser() = runBlocking {
        coEvery { safLocationManager.rememberedLocation(any(), any()) } returns null
        coEvery { safLocationManager.tryPersist(any(), any()) } returns true
        coEvery { resolutionService.resolve(any(), any(), any<LibraryItem>()) } returns resolvedOutcome()

        val source = mockk<Uri>()
        val pickers = object : ImportPickers {
            override suspend fun pickImportSource(): Uri? {
                callOrder += "pickImportSource"
                return source
            }
            override suspend fun openContainerBrowser(container: Container, appId: String, gameId: Int): SaveLocation? {
                callOrder += "openContainerBrowser"
                return saveLocation()
            }
        }

        val outcome = orchestrator().import(context, item, pickers)

        assertEquals(ImportOutcome.Success, outcome)
        assertEquals(listOf("pickImportSource", "engine.import"), callOrder)
        assertTrue("container browser must not open for a known location", "openContainerBrowser" !in callOrder)
        coVerify(exactly = 1) { engine.import(any(), any(), any(), any()) }
    }

    /**
     * Req 5.1a: SAF source open failure aborts the import → [ImportOutcome.Aborted], the engine is
     * NEVER called, AND the container browser is NEVER reached (SAF is first, browser never runs).
     */
    @Test
    fun importAbortsWhenSafSourceFailsToOpen() = runBlocking {
        coEvery { safLocationManager.rememberedLocation(any(), any()) } returns null

        val pickers = object : ImportPickers {
            override suspend fun pickImportSource(): Uri? {
                callOrder += "pickImportSource"
                throw PickerOpenException(PickerOpenException.Picker.SAF_PICKER)
            }
            override suspend fun openContainerBrowser(container: Container, appId: String, gameId: Int): SaveLocation? {
                callOrder += "openContainerBrowser"
                return saveLocation()
            }
        }

        val outcome = orchestrator().import(context, item, pickers)

        assertTrue("SAF source open failure must abort the import, got $outcome", outcome is ImportOutcome.Aborted)
        assertTrue("container browser must never run after SAF open failure", "openContainerBrowser" !in callOrder)
        coVerify(exactly = 0) { engine.import(any(), any(), any(), any()) }
    }

    /**
     * Req 5.8, 13.4 (SAF source cancel): cancelling the SAF source picker (null) yields
     * [ImportOutcome.Cancelled] — never success or failure — and the engine is NEVER called.
     */
    @Test
    fun importSafSourceCancelYieldsCancelledNeverSuccessOrFailure() = runBlocking {
        coEvery { safLocationManager.rememberedLocation(any(), any()) } returns null

        val pickers = object : ImportPickers {
            override suspend fun pickImportSource(): Uri? {
                callOrder += "pickImportSource"
                return null // cancel
            }
            override suspend fun openContainerBrowser(container: Container, appId: String, gameId: Int): SaveLocation? {
                callOrder += "openContainerBrowser"
                return saveLocation()
            }
        }

        val outcome = orchestrator().import(context, item, pickers)

        assertEquals(ImportOutcome.Cancelled, outcome)
        assertTrue("browser must not run after SAF source cancel", "openContainerBrowser" !in callOrder)
        coVerify(exactly = 0) { engine.import(any(), any(), any(), any()) }
    }

    /**
     * Req 5.8, 13.4 (browser cancel): cancelling the container browser (null) after the source was
     * selected yields [ImportOutcome.Cancelled] — never success or failure — and the engine is
     * NEVER called.
     */
    @Test
    fun importBrowserCancelYieldsCancelledNeverSuccessOrFailure() = runBlocking {
        coEvery { safLocationManager.rememberedLocation(any(), any()) } returns null
        coEvery { safLocationManager.tryPersist(any(), any()) } returns true
        coEvery { resolutionService.resolve(any(), any(), any<LibraryItem>()) } returns
            ResolveOutcome.NeedsBrowser(automaticUnavailable = false)

        val pickers = object : ImportPickers {
            override suspend fun pickImportSource(): Uri? {
                callOrder += "pickImportSource"
                return mockk()
            }
            override suspend fun openContainerBrowser(container: Container, appId: String, gameId: Int): SaveLocation? {
                callOrder += "openContainerBrowser"
                return null // cancel
            }
        }

        val outcome = orchestrator().import(context, item, pickers)

        assertEquals(ImportOutcome.Cancelled, outcome)
        coVerify(exactly = 0) { engine.import(any(), any(), any(), any()) }
    }

    /**
     * Req 5.7 (no container changes until both endpoints determined): in the success path the
     * ordered call list shows BOTH endpoints resolved (pickImportSource returned a Uri AND the
     * destination was determined) BEFORE `engine.import` — the engine (the only container writer)
     * is always last. Combined with the abort/cancel tests above (where the engine is never called),
     * this establishes that the container is untouched until both endpoints exist.
     */
    @Test
    fun importEngineRunsOnlyAfterBothEndpointsDetermined() = runBlocking {
        coEvery { safLocationManager.rememberedLocation(any(), any()) } returns null
        coEvery { safLocationManager.tryPersist(any(), any()) } returns true
        coEvery { resolutionService.resolve(any(), any(), any<LibraryItem>()) } returns
            ResolveOutcome.NeedsBrowser(automaticUnavailable = false)

        val pickers = object : ImportPickers {
            override suspend fun pickImportSource(): Uri? {
                callOrder += "pickImportSource"
                return mockk()
            }
            override suspend fun openContainerBrowser(container: Container, appId: String, gameId: Int): SaveLocation? {
                callOrder += "openContainerBrowser"
                return saveLocation()
            }
        }

        val outcome = orchestrator().import(context, item, pickers)

        assertEquals(ImportOutcome.Success, outcome)
        // engine.import is the last call, and both endpoint steps precede it.
        assertEquals("engine.import", callOrder.last())
        assertTrue(
            "source endpoint must be determined before engine.import",
            callOrder.indexOf("pickImportSource") < callOrder.indexOf("engine.import"),
        )
        assertTrue(
            "destination endpoint must be determined before engine.import",
            callOrder.indexOf("openContainerBrowser") < callOrder.indexOf("engine.import"),
        )
    }

    /**
     * Req 6.5 (container unresolvable): resolveContainer throwing aborts the import →
     * [ImportOutcome.Aborted], and pickers and engine are NEVER invoked.
     */
    @Test
    fun importAbortsWhenContainerUnresolvable() = runBlocking {
        val pickers = object : ImportPickers {
            override suspend fun pickImportSource(): Uri? {
                callOrder += "pickImportSource"
                return mockk()
            }
            override suspend fun openContainerBrowser(container: Container, appId: String, gameId: Int): SaveLocation? {
                callOrder += "openContainerBrowser"
                return saveLocation()
            }
        }

        val outcome = orchestrator(resolveContainer = { _, _ ->
            throw IllegalStateException("no container")
        }).import(context, item, pickers)

        assertTrue("unresolvable container must abort the import, got $outcome", outcome is ImportOutcome.Aborted)
        assertTrue("no pickers should run when the container is unresolvable", callOrder.isEmpty())
        coVerify(exactly = 0) { engine.import(any(), any(), any(), any()) }
    }
}
