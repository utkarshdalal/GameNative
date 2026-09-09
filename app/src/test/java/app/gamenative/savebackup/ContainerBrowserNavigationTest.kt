package app.gamenative.savebackup

import com.winlator.xenvironment.ImageFs
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Example tests for [ContainerBrowser] navigation behaviors — game-save-backup Task 8.4.
 *
 * Feature: game-save-backup, Requirement 3 (In-app Container_Browser). These are plain JUnit 4
 * example tests (no property testing, no Robolectric): the browser is pure `java.nio.file` logic,
 * so it is driven directly through [ContainerBrowser.forDriveC] over a temporary `drive_c`
 * directory without constructing a real [com.winlator.container.Container].
 *
 * Each test maps to a single acceptance criterion:
 *  - initialViewIsDriveC .......................... Req 3.1
 *  - openMissingDriveCReportsUnavailable .......... Req 3.2
 *  - openNonDirectoryDriveCReportsUnavailable ..... Req 3.2
 *  - openUnreadableDriveCReportsUnavailable ....... Req 3.2 (skipped if setReadable is a no-op)
 *  - allSevenSaveRootsAreSurfacedWhenPresent ...... Req 3.3
 *  - onlyExistingSaveRootsAreSurfaced ............. Req 3.3
 *  - intoSubdirShowsItsContents ................... Req 3.4
 *  - intoNonexistentChildKeepsViewAndErrors ....... Req 3.5
 *  - intoUnreadableChildKeepsViewAndErrors ........ Req 3.5 (skipped if setReadable is a no-op)
 *  - upFromSubdirShowsParent ...................... Req 3.6
 *  - upDisabledAtDriveCRoot ....................... Req 3.7
 *  - intoThenUpReturnsToRootWhereUpStaysDisabled .. Req 3.7
 */
class ContainerBrowserNavigationTest {

    private val tempRoots = mutableListOf<Path>()

    /** Directories that were made unreadable during a test and must be re-marked before cleanup. */
    private val madeUnreadable = mutableListOf<Path>()

    @After
    fun tearDown() {
        // Restore readability on anything we made unreadable so the tree can be traversed/deleted.
        // We cannot Files.walk() an unreadable directory, so restore these explicitly first.
        madeUnreadable.forEach { it.toFile().setReadable(true) }
        madeUnreadable.clear()
        tempRoots.forEach { deleteRecursively(it) }
        tempRoots.clear()
    }

    /** Attempt to make [dir] unreadable, tracking it for cleanup. Returns whether it took effect. */
    private fun makeUnreadable(dir: Path): Boolean {
        val ok = dir.toFile().setReadable(false) && !dir.toFile().canRead()
        if (ok) madeUnreadable.add(dir)
        return ok
    }

    // Req 3.1: When the browser opens, it displays the contents of drive_c as the initial location.
    @Test
    fun initialViewIsDriveC() {
        val driveC = newTempDir("cb-nav-drivec")
        // Two entries at the drive_c root: a directory and a file.
        val programData = Files.createDirectory(driveC.resolve("ProgramData"))
        val marker = Files.createFile(driveC.resolve("boot.ini"))

        val result = ContainerBrowser.forDriveC(driveC).open()

        assertTrue("open() should be Available for a readable drive_c", result is OpenResult.Available)
        val view = (result as OpenResult.Available).view
        assertEquals("initial view must be drive_c", driveC.normalize(), view.currentDir)

        val listedPaths = view.list().map { it.path }.toSet()
        assertEquals(
            "list() must return the drive_c entries created",
            setOf(programData.normalize(), marker.normalize()),
            listedPaths.map { it.normalize() }.toSet(),
        )
        // Directories are listed before files.
        assertEquals("ProgramData", view.list().first().name)
        assertTrue("directory entry must be flagged isDirectory", view.list().first().isDirectory)
    }

    // Req 3.2: If drive_c does not exist when opening, report the container filesystem unavailable
    // and return no location.
    @Test
    fun openMissingDriveCReportsUnavailable() {
        val parent = newTempDir("cb-nav-missing")
        val missingDriveC = parent.resolve("does-not-exist")

        val result = ContainerBrowser.forDriveC(missingDriveC).open()

        assertTrue("open() on a missing drive_c must be Unavailable", result is OpenResult.Unavailable)
        assertEquals(
            ContainerBrowser.CONTAINER_UNAVAILABLE,
            (result as OpenResult.Unavailable).reason,
        )
    }

    // Req 3.2: A drive_c path that exists but is not a directory is also unavailable.
    @Test
    fun openNonDirectoryDriveCReportsUnavailable() {
        val parent = newTempDir("cb-nav-nondir")
        val fileAsDriveC = Files.createFile(parent.resolve("drive_c-is-a-file"))

        val result = ContainerBrowser.forDriveC(fileAsDriveC).open()

        assertTrue("open() on a non-directory drive_c must be Unavailable", result is OpenResult.Unavailable)
        assertEquals(
            ContainerBrowser.CONTAINER_UNAVAILABLE,
            (result as OpenResult.Unavailable).reason,
        )
    }

    // Req 3.2: An existing-but-unreadable drive_c is also unavailable. Making a directory unreadable
    // is not reliable on all filesystems / as root, so this variant is guarded by an assumption and
    // skipped when setReadable(false) does not take effect.
    @Test
    fun openUnreadableDriveCReportsUnavailable() {
        val driveC = newTempDir("cb-nav-unreadable")
        Files.createDirectory(driveC.resolve("child"))

        assumeTrue(
            "setReadable(false) had no effect on this filesystem; skipping variant",
            makeUnreadable(driveC),
        )

        val result = ContainerBrowser.forDriveC(driveC).open()

        assertTrue("open() on an unreadable drive_c must be Unavailable", result is OpenResult.Unavailable)
        assertEquals(
            ContainerBrowser.CONTAINER_UNAVAILABLE,
            (result as OpenResult.Unavailable).reason,
        )
    }

    // Req 3.3: All seven common Windows save roots that exist within drive_c are surfaced as
    // navigable shortcuts.
    @Test
    fun allSevenSaveRootsAreSurfacedWhenPresent() {
        val driveC = newTempDir("cb-nav-roots")
        val roots = createAllSevenRoots(driveC)

        val view = openView(driveC)
        val shortcuts = view.saveRootShortcuts()

        assertEquals("all seven roots must be surfaced", 7, shortcuts.size)
        val shortcutPaths = shortcuts.map { it.path.normalize() }.toSet()
        assertEquals(
            "surfaced shortcut paths must be exactly the seven created roots",
            roots.values.map { it.normalize() }.toSet(),
            shortcutPaths,
        )
        // Labels cover each expected root.
        val labels = shortcuts.map { it.label }.toSet()
        assertEquals(
            setOf(
                "Documents",
                "Saved Games",
                "AppData/Local",
                "AppData/LocalLow",
                "AppData/Roaming",
                "ProgramData",
                "Program Files",
            ),
            labels,
        )
    }

    // Req 3.3: When only some roots exist, only the existing ones are surfaced.
    @Test
    fun onlyExistingSaveRootsAreSurfaced() {
        val driveC = newTempDir("cb-nav-partial-roots")
        val user = ImageFs.USER
        val usersHome = driveC.resolve("users").resolve(user)
        // Create only three of the seven roots.
        val documents = Files.createDirectories(usersHome.resolve("Documents"))
        val savedGames = Files.createDirectories(usersHome.resolve("Saved Games"))
        val programData = Files.createDirectories(driveC.resolve("ProgramData"))

        val view = openView(driveC)
        val shortcuts = view.saveRootShortcuts()

        assertEquals("only the three existing roots must be surfaced", 3, shortcuts.size)
        assertEquals(
            setOf(documents.normalize(), savedGames.normalize(), programData.normalize()),
            shortcuts.map { it.path.normalize() }.toSet(),
        )
        assertEquals(setOf("Documents", "Saved Games", "ProgramData"), shortcuts.map { it.label }.toSet())
    }

    // Req 3.4: Navigating into a subdirectory displays that subdirectory's contents.
    @Test
    fun intoSubdirShowsItsContents() {
        val driveC = newTempDir("cb-nav-into")
        val subdir = Files.createDirectory(driveC.resolve("Games"))
        val nested = Files.createDirectory(subdir.resolve("Slot1"))
        val save = Files.createFile(subdir.resolve("save.dat"))

        val view = openView(driveC)
        val nav = view.into(subdir)

        assertTrue("into() a readable child must be Ok", nav is NavResult.Ok)
        val subView = (nav as NavResult.Ok).view
        assertEquals("new view must be the subdirectory", subdir.normalize(), subView.currentDir)
        assertEquals(
            "subdir view must list its contents",
            setOf(nested.normalize(), save.normalize()),
            subView.list().map { it.path.normalize() }.toSet(),
        )
    }

    // Req 3.5: Navigating into a directory that cannot be read (here: a nonexistent child) reports
    // an error and keeps the previously displayed view unchanged.
    @Test
    fun intoNonexistentChildKeepsViewAndErrors() {
        val driveC = newTempDir("cb-nav-into-missing")
        Files.createDirectory(driveC.resolve("Games"))

        val view = openView(driveC)
        val missingChild = driveC.resolve("Nope")
        val nav = view.into(missingChild)

        assertTrue("into() a nonexistent child must be Error", nav is NavResult.Error)
        val err = nav as NavResult.Error
        assertEquals("view must be unchanged (still at drive_c)", driveC.normalize(), err.view.currentDir)
        assertTrue(
            "reason must start with the into-error prefix, was: ${err.reason}",
            err.reason.startsWith(ContainerBrowser.INTO_ERROR_PREFIX),
        )
    }

    // Req 3.5: A genuinely unreadable existing subdirectory keeps the view and errors. Guarded by an
    // assumption since setReadable(false) is not reliable on all filesystems.
    @Test
    fun intoUnreadableChildKeepsViewAndErrors() {
        val driveC = newTempDir("cb-nav-into-unreadable")
        val subdir = Files.createDirectory(driveC.resolve("Locked"))
        Files.createFile(subdir.resolve("secret.dat"))

        assumeTrue(
            "setReadable(false) had no effect on this filesystem; skipping variant",
            makeUnreadable(subdir),
        )

        val view = openView(driveC)
        val nav = view.into(subdir)

        assertTrue("into() an unreadable child must be Error", nav is NavResult.Error)
        val err = nav as NavResult.Error
        assertEquals("view must be unchanged (still at drive_c)", driveC.normalize(), err.view.currentDir)
        assertTrue(
            "reason must start with the into-error prefix, was: ${err.reason}",
            err.reason.startsWith(ContainerBrowser.INTO_ERROR_PREFIX),
        )
    }

    // Req 3.6: Navigating upward from a subdirectory displays the parent directory.
    @Test
    fun upFromSubdirShowsParent() {
        val driveC = newTempDir("cb-nav-up")
        val subdir = Files.createDirectory(driveC.resolve("Games"))
        val deeper = Files.createDirectory(subdir.resolve("Slot1"))

        val view = openView(driveC)
        val subView = (view.into(subdir) as NavResult.Ok).view
        val deepView = (subView.into(deeper) as NavResult.Ok).view

        val up = deepView.up()
        assertTrue("up() from a subdir must be Ok", up is NavResult.Ok)
        assertEquals(
            "up() must display the parent directory",
            subdir.normalize(),
            (up as NavResult.Ok).view.currentDir,
        )
    }

    // Req 3.7: At the drive_c root, upward navigation is disabled and up() errors.
    @Test
    fun upDisabledAtDriveCRoot() {
        val driveC = newTempDir("cb-nav-up-root")
        Files.createDirectory(driveC.resolve("Games"))

        val view = openView(driveC)
        assertFalse("canGoUp must be false at the drive_c root", view.canGoUp)

        val up = view.up()
        assertTrue("up() at the root must be Error", up is NavResult.Error)
        val err = up as NavResult.Error
        assertEquals(ContainerBrowser.UP_AT_ROOT_ERROR, err.reason)
        assertEquals("view must remain at drive_c", driveC.normalize(), err.view.currentDir)
    }

    // Req 3.7: After going into a subdir and back up to the root, up stays disabled and navigation
    // cannot go above drive_c.
    @Test
    fun intoThenUpReturnsToRootWhereUpStaysDisabled() {
        val driveC = newTempDir("cb-nav-into-up-root")
        val subdir = Files.createDirectory(driveC.resolve("Games"))

        val root = openView(driveC)
        assertFalse(root.canGoUp)

        val subView = (root.into(subdir) as NavResult.Ok).view
        assertTrue("canGoUp must be true inside a subdir", subView.canGoUp)

        val backAtRoot = (subView.up() as NavResult.Ok).view
        assertEquals("must be back at drive_c", driveC.normalize(), backAtRoot.currentDir)
        assertFalse("canGoUp must be false again at the root", backAtRoot.canGoUp)

        val up = backAtRoot.up()
        assertTrue("up() above drive_c must be Error", up is NavResult.Error)
        assertEquals(ContainerBrowser.UP_AT_ROOT_ERROR, (up as NavResult.Error).reason)
        assertEquals(driveC.normalize(), up.view.currentDir)
        assertNotNull(up.view)
    }

    // ---- helpers -----------------------------------------------------------

    private fun openView(driveC: Path): ContainerBrowser.BrowserView {
        val result = ContainerBrowser.forDriveC(driveC).open()
        assertTrue("expected drive_c to open Available", result is OpenResult.Available)
        return (result as OpenResult.Available).view
    }

    /** Create all seven common Windows save roots under [driveC], returning them by label. */
    private fun createAllSevenRoots(driveC: Path): Map<String, Path> {
        val user = ImageFs.USER
        val usersHome = driveC.resolve("users").resolve(user)
        val roots = linkedMapOf(
            "Documents" to usersHome.resolve("Documents"),
            "Saved Games" to usersHome.resolve("Saved Games"),
            "AppData/Local" to usersHome.resolve("AppData").resolve("Local"),
            "AppData/LocalLow" to usersHome.resolve("AppData").resolve("LocalLow"),
            "AppData/Roaming" to usersHome.resolve("AppData").resolve("Roaming"),
            "ProgramData" to driveC.resolve("ProgramData"),
            "Program Files" to driveC.resolve("Program Files"),
        )
        roots.values.forEach { Files.createDirectories(it) }
        return roots.mapValues { it.value.normalize() }
    }

    private fun newTempDir(prefix: String): Path {
        val dir: Path = Files.createTempDirectory(prefix)
        tempRoots.add(dir)
        return dir
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path: Path -> Files.deleteIfExists(path) }
        }
    }
}
