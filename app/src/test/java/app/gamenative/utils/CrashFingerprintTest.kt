package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashFingerprintTest {

    private val versions = mapOf("wine" to "proton-10.0-arm64ec", "fexcore" to "2611", "graphics_driver_version" to "turnip26.0.0")

    private fun winedbgCrash(tid: String, stack: String) = """
        esync: up and running.
        fixme:ntdll:NtQuerySystemInformation info_class SYSTEM_PERFORMANCE_INFORMATION
        wine: Unhandled page fault on read access to 0000000000000000 at address 00000001400123AB (thread $tid), starting debugger...
        Unhandled exception: page fault on read access to 0x00000000 in 64-bit code (0x00000001400123ab).
        Register dump:
         rip:00000001400123ab rsp:$stack rbp:0000000000000000 eflags:00010246 (  R- --  I  Z- -P- )
        Stack dump:
        0x$stack:  0000000000000000 0000000000000000
        Backtrace:
        =>0 0x00000001400123ab in game (+0x123ab) (0x$stack)
          1 0x0000000140045678 in game (+0x45678) (0x$stack)
          2 0x00006fffffc4a1b2 BaseThreadInitThunk+0x12(unknown=0x0) [Z:\usr\src\wine\dlls\kernel32\thread.c:61] in kernel32 (0x$stack)
          3 0x0000000170012345 RtlUserThreadStart+0x21 in ntdll (0x$stack)
        0x00000001400123ab game+0x123ab: movl (%rax),%eax
        Modules:
        Module	Address			Debug info	Name (52 modules)
        PE	0000000140000000-0000000140200000	Deferred        game
    """.trimIndent()

    @Test
    fun wineUnhandledExceptionWithBacktrace() {
        val fp = CrashFingerprint.extract(winedbgCrash("0124", "000000000011fd80"), "guest_terminated", versions)
        assertNotNull(fp)
        fp!!
        assertEquals("game", fp.component)
        assertEquals("exception", fp.kind)
        assertEquals("c0000005", fp.code)
        assertEquals("game", fp.module)
        assertEquals(listOf("game+0x123ab", "game+0x45678", "kernel32!BaseThreadInitThunk+0x12"), fp.frames)
        assertEquals(16, fp.hash.length)
        assertEquals(versions, fp.versions)
        assertTrue(fp.sample.first().startsWith("Unhandled exception: page fault on read access to 0x…"))
        assertTrue(fp.sample.all { it.length <= 200 && !it.contains("00000001400123ab", ignoreCase = true) })
    }

    @Test
    fun hashIgnoresThreadIdsAndAddresses() {
        val a = CrashFingerprint.extract(winedbgCrash("0124", "000000000011fd80"), "guest_terminated", versions)!!
        val b = CrashFingerprint.extract(winedbgCrash("01a8", "000000000031ee40"), "quick_menu", emptyMap())!!
        assertEquals(a.hash, b.hash)
        assertEquals(a.sample, b.sample)
    }

    @Test
    fun wineUnhandledExceptionWithoutBacktrace() {
        val log = """
            wine: Unhandled illegal instruction at address 000000014000ABCD (thread 0098), starting debugger...
            0098:err:seh:start_debugger Couldn't start debugger L"winedbg --auto 1 2" (2)
        """.trimIndent()
        val fp = CrashFingerprint.extract(log, "guest_terminated", versions)!!
        assertEquals("wine", fp.component)
        assertEquals("exception", fp.kind)
        assertEquals("c000001d", fp.code)
        assertEquals("", fp.module)
        assertTrue(fp.frames.isEmpty())
        assertEquals("wine: Unhandled illegal instruction at address 0x… (thread …), starting debugger...", fp.sample.first())

        val raw = CrashFingerprint.extract(
            "wine: Unhandled exception 0xc0000409 in thread 124 at address 000000014000ABCD (thread 0124)",
            "guest_terminated",
            versions,
        )!!
        assertEquals("c0000409", raw.code)
    }

    @Test
    fun exceptionCodeLineOverridesDescription() {
        val log = """
            Unhandled exception: 0xe06d7363 in 64-bit code (0x00006fffffc4a1b2).
            Exception code: 0xE06D7363
        """.trimIndent()
        val fp = CrashFingerprint.extract(log, "guest_terminated", versions)!!
        assertEquals("e06d7363", fp.code)
        assertEquals(2, fp.sample.size)
    }

    @Test
    fun unimplementedFunctionCall() {
        val log = """
            wine: Call from 00006FFFFFC4A1B2 to unimplemented function api-ms-win-core-psm-appnotify-l1-1-0.dll.RegisterAppStateChangeNotification, aborting
            Unhandled exception: unimplemented function api-ms-win-core-psm-appnotify-l1-1-0.dll.RegisterAppStateChangeNotification called in 64-bit code (0x000000017000f3b8).
        """.trimIndent()
        val fp = CrashFingerprint.extract(log, "guest_terminated", versions)!!
        assertEquals("wine", fp.component)
        assertEquals("unimplemented", fp.kind)
        assertEquals("api-ms-win-core-psm-appnotify-l1-1-0.dll.RegisterAppStateChangeNotification", fp.code)
        assertEquals("api-ms-win-core-psm-appnotify-l1-1-0.dll", fp.module)

        val stubOnly = CrashFingerprint.extract(log.lines().first(), "guest_terminated", versions)!!
        assertEquals(fp.code, stubOnly.code)
        assertEquals(fp.hash, stubOnly.hash)
    }

    @Test
    fun fexUnimplementedInstructionBeatsTheWineException() {
        val log = """
            FEX: [E] Invalid or Unknown instruction: 62 f1 7c 48 10 at RIP 0x00000001400a1b2c
            wine: Unhandled illegal instruction at address 00000001400A1B2C (thread 0130), starting debugger...
            Unhandled exception: illegal instruction in 64-bit code (0x00000001400a1b2c).
        """.trimIndent()
        val fp = CrashFingerprint.extract(log, "guest_terminated", versions)!!
        assertEquals("fex", fp.component)
        assertEquals("unimplemented", fp.kind)
        assertEquals("62 F1 7C 48 10", fp.code)
        assertEquals(3, fp.sample.size)
        assertEquals("FEX: [E] Invalid or Unknown instruction: 62 f1 7c 48 10 at RIP 0x…", fp.sample.first())
    }

    @Test
    fun fexUnhandledSyscall() {
        val fp = CrashFingerprint.extract("FEX: Unhandled syscall: 435", "guest_terminated", versions)!!
        assertEquals("fex", fp.component)
        assertEquals("unimplemented", fp.kind)
        assertEquals("syscall_435", fp.code)
    }

    @Test
    fun box64UnimplementedOpcode() {
        val log = "12345|0x7f12345678: Unimplemented Opcode (C4) C4 E2 79 18 05 00 00 00 00"
        val fp = CrashFingerprint.extract(log, "guest_terminated", versions)!!
        assertEquals("box64", fp.component)
        assertEquals("unimplemented", fp.kind)
        assertEquals("C4 E2 79 18 05 00 00 00 00", fp.code)
        assertEquals("0x…: Unimplemented Opcode (C4) C4 E2 79 18 05 00 00 00 00", fp.sample.first())
    }

    @Test
    fun mesaAssertion() {
        val log = """
            MESA: info: TU: using kgsl
            wine64: ../src/freedreno/vulkan/tu_cmd_buffer.cc:4821: void tu6_emit_blit(tu_cmd_buffer*): Assertion `cmd->state.pass' failed.
            wine: Unhandled exception 0x80000100 in thread 124 at address 00007F00DEADBEEF (thread 0124)
        """.trimIndent()
        val fp = CrashFingerprint.extract(log, "guest_terminated", versions)!!
        assertEquals("mesa", fp.component)
        assertEquals("assert", fp.kind)
        assertEquals("tu_cmd_buffer.cc:4821", fp.code)
        assertEquals("tu_cmd_buffer.cc", fp.module)
    }

    @Test
    fun deviceLostIsAttributedByEmitter() {
        val mesa = CrashFingerprint.extract(
            "MESA: error: ../src/freedreno/vulkan/tu_knl_kgsl.cc:1205: GPU hang detected (VK_ERROR_DEVICE_LOST)",
            "quick_menu",
            versions,
        )!!
        assertEquals("mesa", mesa.component)
        assertEquals("device_lost", mesa.kind)
        assertEquals("tu_knl_kgsl.cc", mesa.code)

        val dxvk = CrashFingerprint.extract("err:   DxvkDevice: waitForSubmission: VK_ERROR_DEVICE_LOST", "guest_terminated", versions)!!
        assertEquals("dxvk", dxvk.component)
        assertEquals("device_lost", dxvk.kind)
    }

    @Test
    fun dxvkAndVkd3dErrors() {
        val dxvk = CrashFingerprint.extract("err:   DxvkError: Failed to create shader module 42", "guest_terminated", versions)!!
        assertEquals("dxvk", dxvk.component)
        assertEquals("other", dxvk.kind)
        assertEquals("Failed to create shader module #", dxvk.code)

        val vkd3d = CrashFingerprint.extract("0124:err:d3d12_device_create_pipeline_state: Failed to compile DXIL.", "guest_terminated", versions)!!
        assertEquals("vkd3d", vkd3d.component)
        assertEquals("d3d12_device_create_pipeline_state", vkd3d.code)
    }

    @Test
    fun missingDllIsALoadFailure() {
        val log = """
            0124:err:module:import_dll Library MSVCP140.dll (which is needed by L"Z:\\games\\game.exe") not found
            0124:err:module:loader_init Importing dlls for L"Z:\\games\\game.exe" failed, status c0000135
        """.trimIndent()
        val fp = CrashFingerprint.extract(log, "guest_terminated", versions)!!
        assertEquals("wine", fp.component)
        assertEquals("load_failure", fp.kind)
        assertEquals("not_found", fp.code)
        assertEquals("msvcp140.dll", fp.module)
        assertTrue(fp.sample.first().startsWith("err:module:import_dll Library MSVCP140.dll"))
    }

    @Test
    fun wineserverLoss() {
        val fp = CrashFingerprint.extract("wine client error:124: write: Bad file descriptor", "guest_terminated", versions)!!
        assertEquals("wine", fp.component)
        assertEquals("wineserver_lost", fp.code)
    }

    private val cleanLog = """
        esync: up and running.
        wine: using fast synchronization.
        fixme:ntdll:NtQuerySystemInformation info_class SYSTEM_PERFORMANCE_INFORMATION
        MESA: info: TU: using kgsl
        Box64 with Dynarec v0.3.2 built on Oct 12 2026
    """.trimIndent()

    @Test
    fun cleanLogOnNormalExitIsNull() {
        assertNull(CrashFingerprint.extract(cleanLog, "quick_menu", versions))
        assertNull(CrashFingerprint.extract("", "force_close", versions))
        assertNull(CrashFingerprint.extract(cleanLog, null, versions))
    }

    @Test
    fun guestTerminatedWithoutPatternIsOther() {
        val fp = CrashFingerprint.extract(cleanLog, "guest_terminated", versions)!!
        assertEquals("none", fp.component)
        assertEquals("other", fp.kind)
        assertEquals("guest_terminated", fp.code)
        assertTrue(fp.sample.isEmpty())
        assertNotEquals(CrashFingerprint.extract(winedbgCrash("0124", "000000000011fd80"), "guest_terminated", versions)!!.hash, fp.hash)
    }

    @Test
    fun serializedSizeIsCapped() {
        val longLines = (1..20).joinToString("\n") { "err:   DxvkError: " + "x".repeat(400) + it }
        val fp = CrashFingerprint.extract(longLines, "guest_terminated", versions)!!
        assertTrue(fp.sample.size <= 5)
        assertTrue(fp.sample.all { it.length <= 200 })
        assertTrue(fp.toJson().length <= CrashFingerprint.MAX_SERIALIZED_CHARS)
    }

    @Test
    fun onlyTheTailIsRead() {
        val head = "Unhandled exception: page fault on read access to 0x00000000 in 64-bit code (0x00000001400123ab).\n"
        val log = head + "y".repeat(CrashFingerprint.TAIL_CHARS)
        assertNull(CrashFingerprint.extract(log, "quick_menu", versions))
    }

    @Test
    fun normalizeLineStripsThreadIdsAndLongHex() {
        assertEquals(
            "err:module:import_dll Library X (thread …) at 0x… facade 0x…",
            CrashFingerprint.normalizeLine("0124:err:module:import_dll Library X (thread 0124) at 00000001400123AB facade 0xdeadbeef"),
        )
        assertFalse(CrashFingerprint.normalizeLine("at 0x1234").contains("…"))
    }

    @Test
    fun guestOutputTailKeepsTheLastBytes() {
        GuestOutputTail.reset()
        repeat(2000) { GuestOutputTail.append("line $it " + "z".repeat(200)) }
        val snapshot = GuestOutputTail.snapshot()
        assertTrue(snapshot.length <= CrashFingerprint.TAIL_CHARS)
        assertTrue(snapshot.endsWith("line 1999 " + "z".repeat(200)))
        assertFalse(snapshot.contains("line 0 "))
        GuestOutputTail.reset()
        assertEquals("", GuestOutputTail.snapshot())
    }
}
