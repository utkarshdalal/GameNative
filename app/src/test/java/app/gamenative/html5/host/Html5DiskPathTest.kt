package app.gamenative.html5.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Html5DiskPathTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test fun exactCaseMatchResolves() {
        val root = tempFolder.newFolder("install")
        java.io.File(root, "package.nw").mkdir()
        java.io.File(root, "package.nw/index.html").writeText("hi")

        val f = Html5DiskPath.resolveCaseInsensitive(root, "package.nw/index.html")
        assertEquals(java.io.File(root, "package.nw/index.html").canonicalPath, f?.canonicalPath)
    }

    // title requests `Ayami_Intro.webm`, disk has `ayami_intro.webm`.
    @Test fun caseDifferingLeafResolves() {
        val root = tempFolder.newFolder("install")
        java.io.File(root, "package.nw").mkdir()
        java.io.File(root, "package.nw/ayami_intro.webm").writeText("blob")

        val f = Html5DiskPath.resolveCaseInsensitive(root, "package.nw/Ayami_Intro.webm")
        assertEquals(java.io.File(root, "package.nw/ayami_intro.webm").canonicalPath, f?.canonicalPath)
    }

    @Test fun caseDifferingIntermediateResolves() {
        val root = tempFolder.newFolder("install")
        java.io.File(root, "img/characters").mkdirs()
        java.io.File(root, "img/characters/hero.png").writeText("png")

        val f = Html5DiskPath.resolveCaseInsensitive(root, "Img/Characters/Hero.png")
        assertEquals(java.io.File(root, "img/characters/hero.png").canonicalPath, f?.canonicalPath)
    }

    @Test fun missingLeafReturnsNull() {
        val root = tempFolder.newFolder("install")
        java.io.File(root, "package.nw").mkdir()
        val f = Html5DiskPath.resolveCaseInsensitive(root, "package.nw/missing.webm")
        assertNull(f)
    }

    @Test fun backslashesNormalizedToForwardSlashes() {
        val root = tempFolder.newFolder("install")
        java.io.File(root, "img").mkdir()
        java.io.File(root, "img/hero.png").writeText("png")

        val f = Html5DiskPath.resolveCaseInsensitive(root, "img\\hero.png")
        assertEquals(java.io.File(root, "img/hero.png").canonicalPath, f?.canonicalPath)
    }

    // ".." rejection is the first line of path-traversal defense (caller still does
    // canonical-prefix containment).
    @Test fun dotDotSegmentRejected() {
        val root = tempFolder.newFolder("install")
        java.io.File(root, "img").mkdir()
        val f = Html5DiskPath.resolveCaseInsensitive(root, "img/../secret")
        assertNull(f)
    }

    @Test fun emptyRelPathReturnsRoot() {
        val root = tempFolder.newFolder("install")
        val f = Html5DiskPath.resolveCaseInsensitive(root, "")
        assertEquals(root.canonicalPath, f?.canonicalPath)
    }

    @Test fun dotSegmentsIgnored() {
        val root = tempFolder.newFolder("install")
        java.io.File(root, "img").mkdir()
        java.io.File(root, "img/hero.png").writeText("png")

        val f = Html5DiskPath.resolveCaseInsensitive(root, "./img/./hero.png")
        assertEquals(java.io.File(root, "img/hero.png").canonicalPath, f?.canonicalPath)
    }

    @Test fun leadingAndTrailingSlashesTrimmed() {
        val root = tempFolder.newFolder("install")
        java.io.File(root, "img").mkdir()
        java.io.File(root, "img/hero.png").writeText("png")

        val f = Html5DiskPath.resolveCaseInsensitive(root, "/img/hero.png/")
        assertEquals(java.io.File(root, "img/hero.png").canonicalPath, f?.canonicalPath)
    }

    // directories resolve too -- the caller decides (ZipAssetInterceptor's listdir walks them;
    // the WebViewScreen path handler rejects with `!isFile`).
    @Test fun resolvesDirectoryNode() {
        val root = tempFolder.newFolder("install")
        java.io.File(root, "img/sprites").mkdirs()

        val f = Html5DiskPath.resolveCaseInsensitive(root, "Img/Sprites")
        assertEquals(java.io.File(root, "img/sprites").canonicalPath, f?.canonicalPath)
        org.junit.Assert.assertTrue("expected a directory", f?.isDirectory == true)
    }

    // nw.App.dataPath -> `C:/Users/xuser/AppData/Roaming/<Game>/save.json`. wine has `users`
    // (lowercase) but no save yet: write mode case-folds existing dirs and appends the new
    // leaf literally.
    @Test fun writeMode_caseFoldsExistingDirs_appendsLiteralNewLeaf() {
        val root = tempFolder.newFolder("prefix")
        java.io.File(root, "users/xuser/AppData/Roaming").mkdirs()

        val f = Html5DiskPath.resolveCaseInsensitive(
            root,
            "Users/xuser/AppData/Roaming/CrossCode/save.json",
            writeSemantics = true,
        )
        assertEquals(
            java.io.File(root, "users/xuser/AppData/Roaming/CrossCode/save.json").canonicalPath,
            f?.canonicalPath,
        )
    }

    @Test fun writeMode_missingLeafReturnsLiteralPath() {
        val root = tempFolder.newFolder("install")
        java.io.File(root, "saves").mkdir()
        val f = Html5DiskPath.resolveCaseInsensitive(root, "saves/game.sav", writeSemantics = true)
        assertEquals(java.io.File(root, "saves/game.sav").canonicalPath, f?.canonicalPath)
        // write mode only constructs the path; the caller decides whether to write.
        org.junit.Assert.assertFalse("path should not exist yet", f?.exists() ?: true)
    }

    @Test fun readMode_missingLeafReturnsNull_writeMode_returnsPath() {
        val root = tempFolder.newFolder("install")
        java.io.File(root, "saves").mkdir()
        assertNull(Html5DiskPath.resolveCaseInsensitive(root, "saves/game.sav"))
        val w = Html5DiskPath.resolveCaseInsensitive(root, "saves/game.sav", writeSemantics = true)
        org.junit.Assert.assertNotNull(w)
    }

    // write mode still rejects `..` (never a valid new-file segment).
    @Test fun writeMode_rejectsDotDot() {
        val root = tempFolder.newFolder("install")
        java.io.File(root, "saves").mkdir()
        assertNull(Html5DiskPath.resolveCaseInsensitive(root, "saves/../escape", writeSemantics = true))
    }
}
