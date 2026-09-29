package com.winlator.container

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContainerOverlayFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(root: File, rel: String, content: String): File =
        File(root, rel).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    private fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun bionicContainer(rootDir: File): Container =
        Container("c1").apply {
            this.rootDir = rootDir
            containerVariant = Container.BIONIC
            wineVersion = "proton-9.0-arm64ec"
        }

    @Test
    fun prune_removesIdenticalAndProtonLinkedFiles_keepsTheRest() {
        val base = tmp.newFolder("base", ".wine")
        val protonLib = tmp.newFolder("proton", "lib", "wine", "aarch64-windows")
        val components = tmp.newFolder("components")
        val root = tmp.newFolder("container")
        val upper = File(root, ".wine")

        write(base, "drive_c/windows/system32/same.dll", "identical")
        write(upper, "drive_c/windows/system32/same.dll", "identical")
        write(base, "drive_c/windows/system32/changed.dll", "original")
        write(upper, "drive_c/windows/system32/changed.dll", "modified")
        write(upper, "drive_c/windows/system32/own.dll", "container-only")
        write(base, "drive_c/windows/system32/linked.dll", "AAAA")
        val protonFile = write(protonLib, "linked.dll", "BBBB")
        Files.createLink(File(upper, "drive_c/windows/system32/linked.dll").toPath(), protonFile.toPath())
        write(base, "drive_c/windows/system32/d3d11.dll", "wine-d3d")
        val componentFile = write(components, "d3d11.dll", "dxvk-d3d")
        Files.createLink(File(upper, "drive_c/windows/system32/d3d11.dll").toPath(), componentFile.toPath())
        write(base, "drive_c/Program Files/Common Files/a.txt", "same")
        write(upper, "drive_c/Program Files/Common Files/a.txt", "same")
        write(upper, "drive_c/Program Files/Game/save.dat", "game")
        write(base, "drive_c/users/xuser/x.txt", "same")
        write(upper, "drive_c/users/xuser/x.txt", "same")

        val container = bionicContainer(root)
        container.putExtra("dedupeVersion", "1")
        val result = ContainerOverlayMigrator.migrate(container, base, listOf(protonLib))

        assertTrue(result.completed)
        assertEquals(3, result.filesPruned)
        assertEquals(4, result.filesKept)
        assertEquals(("identical".length + "same".length).toLong(), result.bytesFreed)
        assertFalse(exists(File(upper, "drive_c/windows/system32/same.dll")))
        assertFalse(exists(File(upper, "drive_c/windows/system32/linked.dll")))
        assertFalse(exists(File(upper, "drive_c/Program Files/Common Files/a.txt")))
        assertEquals("modified", File(upper, "drive_c/windows/system32/changed.dll").readText())
        assertEquals("container-only", File(upper, "drive_c/windows/system32/own.dll").readText())
        assertEquals("dxvk-d3d", File(upper, "drive_c/windows/system32/d3d11.dll").readText())
        assertEquals("game", File(upper, "drive_c/Program Files/Game/save.dat").readText())
        assertTrue(File(upper, "drive_c/users/xuser/x.txt").isFile)
        assertEquals("BBBB", protonFile.readText())

        assertEquals(ContainerOverlay.canonicalHostPath(base), container.basePrefix)
        assertEquals(base.canonicalPath, container.basePrefix)
        assertTrue(container.isOverlay)
        assertEquals("", container.getExtra("dedupeVersion"))
        assertFalse(exists(File(upper, ".gnoverlay/wh")))
    }

    @Test
    fun migrate_storesCanonicalBasePathWhenReachedThroughSymlink() {
        val realProton = tmp.newFolder("imagefs_shared", "proton", "proton-9.0-arm64ec")
        val base = File(realProton, "base_prefix/.wine").apply { mkdirs() }
        val opt = tmp.newFolder("imagefs", "opt")
        Files.createSymbolicLink(File(opt, "proton-9.0-arm64ec").toPath(), realProton.toPath())
        val viaSymlink = File(opt, "proton-9.0-arm64ec/base_prefix/.wine")
        val root = tmp.newFolder("container")
        File(root, ".wine").mkdirs()

        val container = bionicContainer(root)
        ContainerOverlayMigrator.migrate(container, viaSymlink, emptyList())

        assertEquals(base.canonicalPath, container.basePrefix)
        assertFalse(container.basePrefix.contains("/imagefs/opt/"))
    }

    @Test
    fun deleteWithWhiteout_hidesBaseFileAndDropsUpperCopy() {
        val base = tmp.newFolder("base", ".wine")
        val root = tmp.newFolder("container")
        val upper = File(root, ".wine")
        write(base, "drive_c/windows/system32/lsteamclient.dll", "base")
        write(upper, "drive_c/windows/system32/lsteamclient.dll", "upper")
        write(upper, "drive_c/windows/syswow64/only-upper.dll", "upper")
        val container = bionicContainer(root).apply { basePrefix = base.absolutePath }

        assertTrue(ContainerFiles.deleteWithWhiteout(container, "drive_c/windows/system32/lsteamclient.dll"))
        assertTrue(ContainerFiles.deleteWithWhiteout(container, "drive_c/windows/syswow64/only-upper.dll"))
        assertTrue(ContainerFiles.deleteWithWhiteout(container, "drive_c/windows/syswow64/missing.dll"))

        assertFalse(exists(File(upper, "drive_c/windows/system32/lsteamclient.dll")))
        assertTrue(Files.isRegularFile(File(upper, ".gnoverlay/wh/drive_c/windows/system32/lsteamclient.dll").toPath()))
        assertNull(ContainerFiles.resolve(container, "drive_c/windows/system32/lsteamclient.dll"))
        assertFalse(exists(File(upper, "drive_c/windows/syswow64/only-upper.dll")))
        assertFalse(exists(File(upper, ".gnoverlay/wh/drive_c/windows/syswow64/only-upper.dll")))
        assertFalse(exists(File(upper, ".gnoverlay/wh/drive_c/windows/syswow64/missing.dll")))
        assertEquals("base", File(base, "drive_c/windows/system32/lsteamclient.dll").readText())
    }

    @Test
    fun deleteWithWhiteout_whitesOutDirectoriesPresentInBase() {
        val base = tmp.newFolder("base", ".wine")
        val root = tmp.newFolder("container")
        val upper = File(root, ".wine")
        write(base, "drive_c/ProgramData/Game/base.txt", "b")
        write(upper, "drive_c/ProgramData/Game/cache.bin", "u")
        write(upper, ".gnoverlay/wh/drive_c/ProgramData/Game/old.txt", "")
        write(upper, ".gnoverlay/opaque/drive_c/ProgramData/Game/Sub", "")
        val container = bionicContainer(root).apply { basePrefix = base.absolutePath }

        assertTrue(ContainerFiles.deleteWithWhiteout(container, "drive_c/ProgramData/Game"))

        assertFalse(exists(File(upper, "drive_c/ProgramData/Game")))
        assertTrue(Files.isRegularFile(File(upper, ".gnoverlay/wh/drive_c/ProgramData/Game").toPath()))
        assertFalse(exists(File(upper, ".gnoverlay/opaque/drive_c/ProgramData/Game")))
        assertNull(ContainerFiles.resolve(container, "drive_c/ProgramData/Game/base.txt"))
    }

    @Test
    fun deleteWithWhiteout_isPlainDeleteForLegacyContainers() {
        val base = tmp.newFolder("base", ".wine")
        val root = tmp.newFolder("container")
        val upper = File(root, ".wine")
        write(base, "drive_c/windows/system32/a.dll", "b")
        write(upper, "drive_c/windows/system32/a.dll", "u")
        val container = bionicContainer(root).apply {
            basePrefix = base.absolutePath
            containerVariant = Container.GLIBC
        }

        assertTrue(ContainerFiles.deleteWithWhiteout(container, "drive_c/windows/system32/a.dll"))

        assertFalse(exists(File(upper, "drive_c/windows/system32/a.dll")))
        assertFalse(exists(File(upper, ".gnoverlay")))
    }

    @Test
    fun prune_neverTreatsLsteamclientAsIdenticalToBase() {
        val base = tmp.newFolder("base", ".wine")
        val root = tmp.newFolder("container")
        val upper = File(root, ".wine")
        write(base, "drive_c/windows/system32/lsteamclient.dll", "same")
        write(upper, "drive_c/windows/system32/lsteamclient.dll", "same")
        write(base, "drive_c/windows/syswow64/LSTEAMCLIENT.DLL", "same")
        write(upper, "drive_c/windows/syswow64/LSTEAMCLIENT.DLL", "same")

        val result = ContainerOverlayMigrator.migrate(bionicContainer(root), base, emptyList())

        assertEquals(0, result.filesPruned)
        assertEquals(2, result.filesKept)
        assertTrue(File(upper, "drive_c/windows/system32/lsteamclient.dll").isFile)
    }

    @Test
    fun basePrefix_removesAppManagedFiles_andUpgradesLegacyMarker() {
        val baseDir = tmp.newFolder("base_prefix")
        val wine = File(baseDir, ".wine")
        write(wine, "drive_c/windows/system32/lsteamclient.dll", "x")
        write(wine, "drive_c/windows/syswow64/lsteamclient.dll", "x")
        write(wine, "drive_c/windows/system32/kernel32.dll", "k32")
        File(baseDir, ".complete").writeText("1:proton-9.0-arm64ec:31")

        assertFalse(BasePrefix.upgradeLegacyBase(baseDir, "1:proton-9.0-arm64ec:30", "2:proton-9.0-arm64ec:30"))
        assertTrue(BasePrefix.upgradeLegacyBase(baseDir, "1:proton-9.0-arm64ec:31", "2:proton-9.0-arm64ec:31"))

        assertFalse(exists(File(wine, "drive_c/windows/system32/lsteamclient.dll")))
        assertFalse(exists(File(wine, "drive_c/windows/syswow64/lsteamclient.dll")))
        assertEquals("k32", File(wine, "drive_c/windows/system32/kernel32.dll").readText())
        assertTrue(BasePrefix.isComplete(baseDir, "2:proton-9.0-arm64ec:31"))
    }

    @Test
    fun basePrefix_removeAppManagedFiles_stripsStagingBase() {
        val wine = tmp.newFolder("staging", ".wine")
        write(wine, "drive_c/windows/system32/lsteamclient.dll", "x")
        write(wine, "drive_c/windows/syswow64/lsteamclient.dll", "x")
        write(wine, "drive_c/windows/system32/steam_api.dll", "keep")

        assertTrue(BasePrefix.removeAppManagedFiles(wine))

        assertFalse(exists(File(wine, "drive_c/windows/system32/lsteamclient.dll")))
        assertFalse(exists(File(wine, "drive_c/windows/syswow64/lsteamclient.dll")))
        assertTrue(File(wine, "drive_c/windows/system32/steam_api.dll").isFile)
    }

    @Test
    fun createThinPrefix_marksDosdevicesOpaque() {
        val base = tmp.newFolder("base", ".wine")
        Files.createDirectories(File(base, "dosdevices").toPath())
        Files.createSymbolicLink(File(base, "dosdevices/c:").toPath(), Paths.get("../drive_c"))
        Files.createSymbolicLink(File(base, "dosdevices/d:").toPath(), Paths.get("/sdcard/Download"))
        val upper = File(tmp.newFolder("container"), ".wine")

        assertTrue(ContainerOverlay.createThinPrefix(base, upper))
        Files.delete(File(upper, "dosdevices/d:").toPath())

        assertTrue(Files.isRegularFile(File(upper, ".gnoverlay/opaque/dosdevices").toPath()))
        assertNull(ContainerFiles.resolve(upper, base, "dosdevices/d:"))
        assertTrue(Files.isSymbolicLink(ContainerFiles.resolve(upper, base, "dosdevices/c:")!!.toPath()))
        assertEquals(File(upper, "dosdevices/c:"), ContainerFiles.resolve(upper, base, "dosdevices/c:"))
    }

    @Test
    fun migrate_marksDosdevicesOpaque() {
        val base = tmp.newFolder("base", ".wine")
        Files.createDirectories(File(base, "dosdevices").toPath())
        Files.createSymbolicLink(File(base, "dosdevices/e:").toPath(), Paths.get("/sdcard"))
        val root = tmp.newFolder("container")
        Files.createDirectories(File(root, ".wine/dosdevices").toPath())

        ContainerOverlayMigrator.migrate(bionicContainer(root), base, emptyList())

        assertTrue(Files.isRegularFile(File(root, ".wine/.gnoverlay/opaque/dosdevices").toPath()))
        assertNull(ContainerFiles.resolve(File(root, ".wine"), base, "dosdevices/e:"))
    }

    @Test
    fun basePrefix_normalizeKeepsOnlyCAndZDrives() {
        val wine = tmp.newFolder("base_prefix", ".wine")
        val dosdevices = File(wine, "dosdevices").apply { mkdirs() }
        Files.createSymbolicLink(File(dosdevices, "c:").toPath(), Paths.get("../drive_c"))
        Files.createSymbolicLink(File(dosdevices, "z:").toPath(), Paths.get("/"))
        Files.createSymbolicLink(File(dosdevices, "a:").toPath(), Paths.get("/storage/game"))
        Files.createSymbolicLink(File(dosdevices, "d:").toPath(), Paths.get("/sdcard/Download"))
        Files.createSymbolicLink(File(dosdevices, "e:").toPath(), Paths.get("/sdcard"))

        assertTrue(BasePrefix.normalize(wine))

        assertEquals(setOf("c:", "z:"), dosdevices.list()!!.toSet())
    }

    @Test
    fun basePrefix_upgradesVersion2BaseByTrimmingDosdevices() {
        val baseDir = tmp.newFolder("base_prefix")
        val dosdevices = File(baseDir, ".wine/dosdevices").apply { mkdirs() }
        Files.createSymbolicLink(File(dosdevices, "c:").toPath(), Paths.get("../drive_c"))
        Files.createSymbolicLink(File(dosdevices, "d:").toPath(), Paths.get("/sdcard/Download"))
        File(baseDir, ".complete").writeText("2:proton-10.0-arm64ec:31")

        assertTrue(BasePrefix.upgradeLegacyBase(baseDir, "2:proton-10.0-arm64ec:31", "3:proton-10.0-arm64ec:31"))

        assertEquals(setOf("c:", "z:"), dosdevices.list()!!.toSet())
        assertEquals("/", Files.readSymbolicLink(File(dosdevices, "z:").toPath()).toString())
        assertTrue(BasePrefix.isComplete(baseDir, "3:proton-10.0-arm64ec:31"))
    }

    @Test
    fun createThinPrefix_copiesSkeletonFromBase() {
        val base = tmp.newFolder("base", ".wine")
        write(base, "system.reg", "sys")
        write(base, "user.reg", "usr")
        write(base, "userdef.reg", "def")
        write(base, "drive_c/users/xuser/Documents/readme.txt", "doc")
        write(base, "drive_c/windows/system32/kernel32.dll", "k32")
        Files.createDirectories(File(base, "dosdevices").toPath())
        Files.createSymbolicLink(File(base, "dosdevices/c:").toPath(), Paths.get("../drive_c"))
        Files.createSymbolicLink(File(base, "dosdevices/z:").toPath(), Paths.get("/"))
        val upper = File(tmp.newFolder("container"), ".wine")

        assertTrue(ContainerOverlay.createThinPrefix(base, upper))

        assertTrue(Files.isSymbolicLink(File(upper, "dosdevices/c:").toPath()))
        assertEquals("../drive_c", Files.readSymbolicLink(File(upper, "dosdevices/c:").toPath()).toString())
        assertEquals("/", Files.readSymbolicLink(File(upper, "dosdevices/z:").toPath()).toString())
        assertEquals("sys", File(upper, "system.reg").readText())
        assertEquals("usr", File(upper, "user.reg").readText())
        assertEquals("def", File(upper, "userdef.reg").readText())
        assertEquals("doc", File(upper, "drive_c/users/xuser/Documents/readme.txt").readText())
        assertTrue(File(upper, "drive_c/windows/temp").isDirectory)
        assertTrue(File(upper, ".gnoverlay").isDirectory)
        assertFalse(exists(File(upper, "drive_c/windows/system32/kernel32.dll")))
    }

    @Test
    fun resolve_prefersUpperThenBaseUnlessWhitedOut() {
        val base = tmp.newFolder("base", ".wine")
        val upper = File(tmp.newFolder("container"), ".wine")
        write(base, "drive_c/windows/system32/a.dll", "base-a")
        write(base, "drive_c/windows/system32/b.dll", "base-b")
        write(upper, "drive_c/windows/system32/b.dll", "upper-b")
        write(base, "drive_c/windows/system32/c.dll", "base-c")
        write(upper, ".gnoverlay/wh/drive_c/windows/system32/c.dll", "")
        write(base, "drive_c/ProgramData/Dir/d.txt", "base-d")
        write(upper, ".gnoverlay/wh/drive_c/ProgramData/Dir", "")
        write(base, "drive_c/ProgramData/Opaque/e.txt", "base-e")
        write(upper, "drive_c/ProgramData/Opaque/f.txt", "upper-f")
        write(upper, ".gnoverlay/opaque/drive_c/ProgramData/Opaque", "")

        assertEquals("base-a", ContainerFiles.resolve(upper, base, "drive_c/windows/system32/a.dll")!!.readText())
        assertEquals("upper-b", ContainerFiles.resolve(upper, base, "drive_c/windows/system32/b.dll")!!.readText())
        assertEquals("base-a", ContainerFiles.resolve(upper, base, "drive_c\\windows\\system32\\a.dll")!!.readText())
        assertNull(ContainerFiles.resolve(upper, base, "drive_c/windows/system32/c.dll"))
        assertNull(ContainerFiles.resolve(upper, base, "drive_c/ProgramData/Dir/d.txt"))
        assertNull(ContainerFiles.resolve(upper, base, "drive_c/ProgramData/Opaque/e.txt"))
        assertEquals("upper-f", ContainerFiles.resolve(upper, base, "drive_c/ProgramData/Opaque/f.txt")!!.readText())
        assertNull(ContainerFiles.resolve(upper, base, "drive_c/windows/system32/missing.dll"))
        assertNull(ContainerFiles.resolve(upper, null, "drive_c/windows/system32/a.dll"))
    }

    @Test
    fun resolve_usesBaseOnlyForOverlayContainers() {
        val base = tmp.newFolder("base", ".wine")
        val root = tmp.newFolder("container")
        write(base, "drive_c/windows/system32/a.dll", "base-a")
        val container = bionicContainer(root).apply { basePrefix = base.absolutePath }

        assertEquals("base-a", ContainerFiles.resolve(container, "drive_c/windows/system32/a.dll")!!.readText())
        container.containerVariant = Container.GLIBC
        assertNull(ContainerFiles.resolve(container, "drive_c/windows/system32/a.dll"))
    }

    @Test
    fun removeOverride_dropsUpperFileAndWhiteout() {
        val base = tmp.newFolder("base", ".wine")
        val upper = File(tmp.newFolder("container"), ".wine")
        write(base, "drive_c/windows/system32/d3d11.dll", "wine")
        write(upper, "drive_c/windows/system32/d3d11.dll", "dxvk")
        write(base, "drive_c/windows/syswow64/d3d11.dll", "wine32")
        write(upper, ".gnoverlay/wh/drive_c/windows/syswow64/d3d11.dll", "")

        assertTrue(ContainerFiles.removeOverride(upper, "drive_c/windows/system32/d3d11.dll"))
        assertTrue(ContainerFiles.removeOverride(upper, "drive_c/windows/syswow64/d3d11.dll"))

        assertEquals("wine", ContainerFiles.resolve(upper, base, "drive_c/windows/system32/d3d11.dll")!!.readText())
        assertEquals("wine32", ContainerFiles.resolve(upper, base, "drive_c/windows/syswow64/d3d11.dll")!!.readText())
    }
}
