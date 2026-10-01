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
            ),
            env,
        )
    }

    @Test
    fun buildEnv_addsDebugOnlyWhenRequested() {
        val env = ContainerOverlay.buildEnv(upper, lower, expectedAliases, true)
        assertEquals("1", env["GN_OVERLAY_DEBUG"])
        assertEquals(4, env.size)
    }

    @Test
    fun appendPreload_appendsAfterExistingEntries() {
        val lib = "/data/app/x/lib/arm64/libgnoverlay.so"
        assertEquals(lib, ContainerOverlay.appendPreload("", lib, ":"))
        assertEquals(lib, ContainerOverlay.appendPreload(null, lib, ":"))
        assertEquals("a.so:libredirect.so:$lib", ContainerOverlay.appendPreload("a.so:libredirect.so", lib, ":"))
        assertEquals("a.so $lib", ContainerOverlay.appendPreload("a.so", lib, " "))
        assertEquals("a.so:$lib", ContainerOverlay.appendPreload("a.so:$lib", lib, ":"))
    }
}
