package com.winlator.container

import org.junit.Assert.assertEquals
import org.junit.Test

class ContainerOverlayEnvTest {
    private val upper = "/data/user/0/app.gamenative/files/imagefs_shared/home/xuser-STEAM_1/.wine"
    private val lower = "/data/user/0/app.gamenative/files/imagefs_shared/proton/proton-10.0-arm64ec/base_prefix/.wine"

    private val expectedAliases = listOf(
        "/data/user/0/app.gamenative/files/imagefs/home/xuser/.wine",
        "/data/data/app.gamenative/files/imagefs/home/xuser/.wine",
        "/data/user/0/app.gamenative/files/imagefs_shared/home/xuser/.wine",
        "/data/data/app.gamenative/files/imagefs_shared/home/xuser/.wine",
        "/home/xuser/.wine",
        "/data/data/app.gamenative/files/imagefs_shared/home/xuser-STEAM_1/.wine",
    )

    @Test
    fun normalizeDataPath_rewritesDataDataOnly() {
        assertEquals(
            "/data/user/0/app.gamenative/files/x",
            ContainerOverlay.normalizeDataPath("/data/data/app.gamenative/files/x"),
        )
        assertEquals("/data/user/0/app.gamenative/x", ContainerOverlay.normalizeDataPath("/data/user/0/app.gamenative/x"))
        assertEquals("/storage/emulated/0", ContainerOverlay.normalizeDataPath("/storage/emulated/0"))
        assertEquals("/data/data/app.gamenative/x", ContainerOverlay.dataDataSpelling("/data/user/0/app.gamenative/x"))
    }

    @Test
    fun aliases_listAllOtherSpellingsOfUpper() {
        val aliases = ContainerOverlay.aliases(
            upper,
            "/data/user/0/app.gamenative/files/imagefs",
            "/data/user/0/app.gamenative/files/imagefs_shared",
        )
        assertEquals(expectedAliases, aliases)
    }

    @Test
    fun aliases_normalizeDataDataRoots() {
        val aliases = ContainerOverlay.aliases(
            upper,
            "/data/data/app.gamenative/files/imagefs/",
            "/data/data/app.gamenative/files/imagefs_shared",
        )
        assertEquals(expectedAliases, aliases)
    }

    @Test
    fun buildEnv_producesExactValues() {
        val env = ContainerOverlay.buildEnv(upper, lower, expectedAliases, false)
        assertEquals(
            linkedMapOf(
                "GN_OVERLAY_UPPER" to upper,
                "GN_OVERLAY_LOWER" to lower,
                "GN_OVERLAY_ALIASES" to expectedAliases.joinToString(":"),
                "GN_OVERLAY_HIDE" to ".DepotDownloader:.DownloadInfo",
            ),
            env,
        )
    }

    @Test
    fun buildEnv_addsDebugOnlyWhenRequested() {
        val env = ContainerOverlay.buildEnv(upper, lower, expectedAliases, true)
        assertEquals("1", env["GN_OVERLAY_DEBUG"])
        assertEquals(5, env.size)
    }

    @Test
    fun insertPreload_appendsWhenThereIsNoRedirect() {
        val lib = "/data/app/x/lib/arm64/libgnoverlay.so"
        assertEquals(lib, ContainerOverlay.insertPreload("", lib, ":"))
        assertEquals(lib, ContainerOverlay.insertPreload(null, lib, ":"))
        assertEquals("a.so:b.so:$lib", ContainerOverlay.insertPreload("a.so:b.so", lib, ":"))
        assertEquals("a.so $lib", ContainerOverlay.insertPreload("a.so", lib, " "))
    }

    @Test
    fun insertPreload_goesAheadOfRedirect() {
        val lib = "/data/app/x/lib/arm64/libgnoverlay.so"
        val redirect = "/data/user/0/app.gamenative/files/imagefs/usr/lib/libredirect-bionic-wx.so"
        assertEquals("a.so:$lib:$redirect", ContainerOverlay.insertPreload("a.so:$redirect", lib, ":"))
        assertEquals("$lib:$redirect:b.so", ContainerOverlay.insertPreload("$redirect:b.so", lib, ":"))
        assertEquals(
            "a.so:$lib:libredirect-bionic-wx-minimal.so:b.so",
            ContainerOverlay.insertPreload("a.so:libredirect-bionic-wx-minimal.so:b.so", lib, ":"),
        )
    }

    @Test
    fun insertPreload_leavesAnExistingEntryAlone() {
        val lib = "/data/app/x/lib/arm64/libgnoverlay.so"
        assertEquals("a.so:$lib", ContainerOverlay.insertPreload("a.so:$lib", lib, ":"))
        assertEquals("$lib:libredirect.so", ContainerOverlay.insertPreload("$lib:libredirect.so", lib, ":"))
    }
}
