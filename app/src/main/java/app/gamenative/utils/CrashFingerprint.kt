package app.gamenative.utils

import java.security.MessageDigest

/** Last 256 KB of guest stdout/stderr, fed by the reader threads ProcessHelper already runs for every launch. */
object GuestOutputTail {
    private const val MAX_CHARS = CrashFingerprint.TAIL_CHARS
    private const val MAX_LINE_CHARS = CrashFingerprint.MAX_LINE_CHARS

    private val lines = ArrayDeque<String>()
    private var chars = 0

    fun append(line: String?) {
        if (line == null) return
        val kept = if (line.length > MAX_LINE_CHARS) line.substring(0, MAX_LINE_CHARS) else line
        synchronized(lines) {
            lines.addLast(kept)
            chars += kept.length + 1
            while (chars > MAX_CHARS && lines.size > 1) chars -= lines.removeFirst().length + 1
        }
    }

    fun reset() {
        synchronized(lines) {
            lines.clear()
            chars = 0
        }
    }

    fun snapshot(): String = synchronized(lines) { lines.joinToString("\n") }
}

object CrashFingerprint {
    const val TAIL_CHARS = 256 * 1024
    const val MAX_LINE_CHARS = 4096
    const val MAX_SERIALIZED_CHARS = 2048
    private const val MAX_SAMPLE_LINES = 5
    private const val MAX_SAMPLE_CHARS = 200
    private const val MAX_FRAMES = 3
    private const val MAX_CODE_CHARS = 96
    private const val MAX_MODULE_CHARS = 64
    private const val MAX_FRAME_CHARS = 120
    private const val EXCEPTION_SCAN_LINES = 400

    private val ABNORMAL_EXITS = setOf("guest_terminated")

    data class Fingerprint(
        val component: String,
        val kind: String,
        val code: String,
        val module: String,
        val frames: List<String>,
        val sample: List<String>,
        val hash: String,
        val versions: Map<String, String>,
    ) {
        fun toMap(): Map<String, Any> = linkedMapOf(
            "component" to component,
            "kind" to kind,
            "code" to code,
            "module" to module,
            "frames" to frames,
            "sample" to sample,
            "hash" to hash,
            "versions" to versions,
        )

        fun toJson(): String = buildString {
            append('{')
            append("\"component\":").append(quote(component)).append(',')
            append("\"kind\":").append(quote(kind)).append(',')
            append("\"code\":").append(quote(code)).append(',')
            append("\"module\":").append(quote(module)).append(',')
            append("\"frames\":").append(frames.joinToString(",", "[", "]") { quote(it) }).append(',')
            append("\"sample\":").append(sample.joinToString(",", "[", "]") { quote(it) }).append(',')
            append("\"hash\":").append(quote(hash)).append(',')
            append("\"versions\":").append(versions.entries.joinToString(",", "{", "}") { "${quote(it.key)}:${quote(it.value)}" })
            append('}')
        }
    }

    private class Hit(
        val priority: Int,
        val index: Int,
        val component: String,
        val kind: String,
        val code: String,
        val module: String = "",
        val frames: List<String> = emptyList(),
        val extraLines: List<Int> = emptyList(),
    )

    private val WINEDBG_UNHANDLED = Regex("""Unhandled exception: (.+?) in (?:32|64)-bit code \(0x[0-9a-fA-F]+\)""")
    private val WINE_UNHANDLED = Regex("""wine: Unhandled (.+?) at address (?:0x)?[0-9a-fA-F]+""")
    private val UNIMPLEMENTED_CALLED = Regex("""unimplemented function (\S+) called""")
    private val WINE_STUB = Regex("""Call from (?:0x)?[0-9a-fA-F]+ to unimplemented function ([^\s,]+)""")
    private val EXCEPTION_CODE = Regex("""[Ee]xception [Cc]ode:?\s*(?:0x)?([0-9a-fA-F]{8})""")
    private val RAW_CODE = Regex("""^(?:exception )?0x([0-9a-fA-F]{8})\b""")
    private val BACKTRACE = Regex("""^\s*Backtrace:""")
    private val FRAME = Regex("""^\s*(?:=>)?\s*(\d+)\s+0x[0-9a-fA-F]+\s+(.*)$""")
    private val FRAME_MODULE = Regex("""\bin\s+([^\s(]+)(?:\s+\(\+0x([0-9a-fA-F]+)\))?""")
    private val FRAME_SYMBOL = Regex("""^([A-Za-z_?@$][\w?@$:<>~]*)\+0x([0-9a-fA-F]+)""")
    private val FAULT_INSTRUCTION = Regex("""^0x[0-9a-fA-F]+ ([^\s+]+)\+0x([0-9a-fA-F]+):""")
    private val IMPORT_FAILED = Regex("""err:module:import_dll (?:Library|Loading library) (\S+) .*?(not found|failed(?: \(error ([0-9a-fA-F]+)\))?)""")
    private val MODULE_FAILED = Regex("""err:module:\S+\s+.*?L?"?([^"\s\\/]+\.(?:dll|exe|drv|sys|ocx))"?.*fail""", RegexOption.IGNORE_CASE)
    private val WINESERVER_LOST = Regex("""wine client error:|wineserver.*(?:crash|died|killed|terminated)|server_protocol_error""", RegexOption.IGNORE_CASE)

    private val BOX64_OPCODE = Regex("""Unimplemented Opcode \(([0-9A-Fa-f]+)\)((?:\s+[0-9A-Fa-f]{2}\b)+)""")
    private val BOX64_SYSCALL = Regex("""Unsupported Syscall 0x[0-9a-fA-F]+h \((\d+)\)""")
    private val BOX64_DYNAREC = Regex("""dynarec.*(?:error|fail|unimplemented|missing)""", RegexOption.IGNORE_CASE)
    private val FEX_INSTRUCTION = Regex("""(?:Unhandled|Unknown|Invalid or Unknown|Unimplemented)(?: x86(?:-64)?)? (?:instruction|opcode)[:\s]*(.*)""", RegexOption.IGNORE_CASE)
    private val FEX_SYSCALL = Regex("""(?:Unhandled|Unimplemented|Unsupported) syscall[:\s#]*(\d+)""", RegexOption.IGNORE_CASE)
    private val SIGNAL = Regex("""\b(SIGILL|SIGSEGV|SIGBUS|SIGFPE|SIGABRT)\b""")
    private val INSTRUCTION_BYTES = Regex("""(?:\b[0-9a-fA-F]{2}\b[ ,]*){2,}""")

    private val ASSERTION = Regex("""([^\s:]+\.(?:c|cc|cpp|cxx|h|hpp)):(\d+):.*?[Aa]ssertion [`'"](.+?)['"] failed""")
    private val SOURCE_FILE = Regex("""([\w\-]+\.(?:c|cc|cpp|cxx|h|hpp)):\d+""")
    private val DEVICE_LOST = Regex("""VK_ERROR_DEVICE_LOST|\bdevice lost\b|\bgpu hang\b|\bGPU hang\b""", RegexOption.IGNORE_CASE)
    private val MESA_ERROR = Regex("""(?:MESA(?:-LOADER)?|TU|turnip): error: (.*)""", RegexOption.IGNORE_CASE)
    private val VKD3D_ERR = Regex("""(?:^|:)err:((?:vkd3d|d3d12|dxil)\w*):?\s*(.*)""", RegexOption.IGNORE_CASE)
    private val DXVK_ERR = Regex("""(?:^|:)err:\s{2,}(.*)""")
    private val DXVK_ERROR = Regex("""DxvkError:?\s*(.*)""")

    private val LONG_HEX = Regex("""\b(?:0x[0-9a-fA-F]{6,}|(?=[0-9a-fA-F]*\d)[0-9a-fA-F]{6,})\b""")
    private val WINE_TID = Regex("""^(?:[0-9]+\.[0-9]+:)?(?:[0-9a-fA-F]{4,8}:){1,2}(?=(?:err|warn|fixme|trace|info):)""")
    private val THREAD_ID = Regex("""\b(thread|tid|pid)([ =:]+)(?:0x)?[0-9a-fA-F]+\b""", RegexOption.IGNORE_CASE)
    private val PID_PREFIX = Regex("""^\[?\d+\]?\|""")
    private val DIGITS = Regex("""\d+""")

    private val DESC_CODES = listOf(
        "page fault" to "c0000005",
        "illegal instruction" to "c000001d",
        "privileged instruction" to "c0000096",
        "stack overflow" to "c00000fd",
        "divide by zero" to "c0000094",
        "division by zero" to "c0000094",
        "integer overflow" to "c0000095",
        "breakpoint" to "80000003",
        "single step" to "80000004",
        "datatype misalignment" to "80000002",
        "floating point" to "c0000090",
        "assertion failed" to "c0000420",
        "stack buffer overrun" to "c0000409",
    )

    private val DXVK_MODULES = setOf("d3d11", "dxgi", "d3d9", "d3d10core", "d3d10", "d3d10_1", "d3d8", "dxvk")
    private val VKD3D_MODULES = setOf("d3d12", "d3d12core", "vkd3d", "vkd3d-proton")
    private val FEX_MODULES = setOf("libarm64ecfex", "libwow64fex", "xtajit", "xtajit64", "fexcore")
    private val WINE_MODULES = setOf(
        "ntdll", "kernelbase", "kernel32", "win32u", "user32", "gdi32", "winevulkan", "wow64", "wow64win",
        "wow64cpu", "winex11", "wined3d", "ole32", "combase", "rpcrt4", "advapi32", "ucrtbase", "msvcrt",
        "ws2_32", "winmm", "dsound", "xaudio2_7", "xinput1_3", "mfplat", "opengl32", "steam_api", "steam_api64",
    )

    fun extract(tail: String, exitReason: String?, componentVersions: Map<String, String>): Fingerprint? {
        val text = if (tail.length > TAIL_CHARS) tail.substring(tail.length - TAIL_CHARS) else tail
        // same cap as GuestOutputTail; some patterns backtrack quadratically on one very long line.
        val lines = text.lines().map { it.trimEnd('\r').take(MAX_LINE_CHARS) }
        val hits = ArrayList<Hit>()
        for (i in lines.indices) matchLine(lines, i)?.let(hits::add)

        val best = hits.maxWithOrNull(compareBy<Hit>({ it.priority }, { it.index }))
        if (best == null) {
            if (exitReason == null || exitReason !in ABNORMAL_EXITS) return null
            return build("none", "other", exitReason, "", emptyList(), emptyList(), componentVersions)
        }
        val sampleIdx = LinkedHashSet<Int>()
        sampleIdx.add(best.index)
        sampleIdx.addAll(best.extraLines)
        hits.filter { it !== best }
            .sortedWith(compareByDescending<Hit> { it.priority }.thenByDescending { it.index })
            .forEach { sampleIdx.add(it.index) }
        val sample = sampleIdx.take(MAX_SAMPLE_LINES).map { normalizeLine(lines[it]).take(MAX_SAMPLE_CHARS) }
        return build(best.component, best.kind, best.code, best.module, best.frames, sample, componentVersions)
    }

    fun normalizeLine(line: String): String {
        var s = line.trim()
        s = WINE_TID.replace(s, "")
        s = PID_PREFIX.replace(s, "")
        s = THREAD_ID.replace(s) { "${it.groupValues[1]}${it.groupValues[2]}…" }
        s = LONG_HEX.replace(s, "0x…")
        return s
    }

    private fun normalizeCode(raw: String): String =
        DIGITS.replace(normalizeLine(raw), "#").trim().take(MAX_CODE_CHARS)

    private fun build(
        component: String,
        kind: String,
        code: String,
        module: String,
        frames: List<String>,
        sample: List<String>,
        versions: Map<String, String>,
    ): Fingerprint {
        val c = code.take(MAX_CODE_CHARS)
        val m = module.take(MAX_MODULE_CHARS)
        val f = frames.take(MAX_FRAMES).map { it.take(MAX_FRAME_CHARS) }
        val hash = sha256("$component|$kind|$c|$m|${f.joinToString(",")}").take(16)
        var fp = Fingerprint(component, kind, c, m, f, sample, hash, versions)
        while (fp.toJson().length > MAX_SERIALIZED_CHARS && fp.sample.isNotEmpty()) {
            fp = fp.copy(sample = fp.sample.dropLast(1))
        }
        if (fp.toJson().length > MAX_SERIALIZED_CHARS) {
            fp = fp.copy(versions = fp.versions.mapValues { it.value.take(48) }.entries.take(8).associate { it.key.take(32) to it.value })
        }
        return fp
    }

    private fun matchLine(lines: List<String>, i: Int): Hit? {
        val line = lines[i]
        if (line.isBlank()) return null
        val lower = line.lowercase()

        BOX64_OPCODE.find(line)?.let { m ->
            return Hit(100, i, "box64", "unimplemented", bytes(m.groupValues[2]).ifEmpty { m.groupValues[1].uppercase() })
        }
        if (!lower.contains("box64")) {
            FEX_INSTRUCTION.find(line)?.let { m ->
                if (lower.contains("fex") || lower.contains("instruction")) {
                    val rest = m.groupValues[1]
                    val code = INSTRUCTION_BYTES.find(rest)?.let { bytes(it.value) }?.ifEmpty { null } ?: normalizeCode(rest)
                    return Hit(100, i, "fex", "unimplemented", code)
                }
            }
        }

        ASSERTION.find(line)?.let { m ->
            val file = m.groupValues[1].substringAfterLast('/')
            return Hit(90, i, componentForPath(m.groupValues[1]), "assert", "$file:${m.groupValues[2]}", file)
        }

        if (DEVICE_LOST.containsMatchIn(line)) {
            val component = when {
                VKD3D_ERR.containsMatchIn(line) || lower.contains("vkd3d") || lower.contains("d3d12") -> "vkd3d"
                DXVK_ERR.containsMatchIn(line) || lower.contains("dxvk") || lower.contains("dxgi") || lower.contains("d3d11") || lower.contains("d3d9") -> "dxvk"
                else -> "mesa"
            }
            val file = SOURCE_FILE.find(line)?.groupValues?.get(1).orEmpty()
            return Hit(85, i, component, "device_lost", if (file.isNotEmpty()) file else "VK_ERROR_DEVICE_LOST", file)
        }

        WINEDBG_UNHANDLED.find(line)?.let { m -> return wineException(lines, i, m.groupValues[1], 82) }
        WINE_UNHANDLED.find(line)?.let { m -> return wineException(lines, i, m.groupValues[1], 80) }

        WINE_STUB.find(line)?.let { m ->
            val (dll, fn) = splitDllFunction(m.groupValues[1])
            return Hit(75, i, "wine", "unimplemented", "$dll.$fn", dll)
        }

        if (lower.contains("fex") || lower.contains("box64")) {
            SIGNAL.find(line)?.let { m ->
                return Hit(70, i, if (lower.contains("box64")) "box64" else "fex", "exception", m.groupValues[1])
            }
        }

        IMPORT_FAILED.find(line)?.let { m ->
            val dll = m.groupValues[1].lowercase()
            val code = m.groupValues[3].ifEmpty { if (m.groupValues[2].startsWith("not")) "not_found" else "failed" }
            return Hit(60, i, "wine", "load_failure", code.lowercase(), dll)
        }
        MODULE_FAILED.find(line)?.let { m ->
            return Hit(58, i, "wine", "load_failure", "failed", m.groupValues[1].lowercase())
        }

        BOX64_SYSCALL.find(line)?.let { m -> return Hit(50, i, "box64", "unimplemented", "syscall_${m.groupValues[1]}") }
        FEX_SYSCALL.find(line)?.let { m ->
            return Hit(50, i, if (lower.contains("box64")) "box64" else "fex", "unimplemented", "syscall_${m.groupValues[1]}")
        }
        if (lower.contains("box64") || lower.startsWith("dynarec")) {
            BOX64_DYNAREC.find(line)?.let { return Hit(45, i, "box64", "other", normalizeCode(it.value)) }
        }

        VKD3D_ERR.find(line)?.let { m ->
            return Hit(40, i, "vkd3d", "other", m.groupValues[1].lowercase())
        }
        DXVK_ERROR.find(line)?.let { m -> return Hit(40, i, "dxvk", "other", normalizeCode(m.groupValues[1].ifEmpty { "DxvkError" })) }
        DXVK_ERR.find(line)?.let { m -> return Hit(40, i, "dxvk", "other", normalizeCode(m.groupValues[1])) }

        MESA_ERROR.find(line)?.let { m -> return Hit(35, i, "mesa", "other", normalizeCode(m.groupValues[1])) }

        if (WINESERVER_LOST.containsMatchIn(line)) return Hit(30, i, "wine", "other", "wineserver_lost")
        return null
    }

    private fun wineException(lines: List<String>, start: Int, desc: String, priority: Int): Hit {
        UNIMPLEMENTED_CALLED.find(desc)?.let { m ->
            val (dll, fn) = splitDllFunction(m.groupValues[1])
            return Hit(priority, start, "wine", "unimplemented", "$dll.$fn", dll)
        }
        var code = codeForDescription(desc)
        val frames = ArrayList<String>()
        var faultModule = ""
        val extra = ArrayList<Int>()
        var inBacktrace = false
        val end = minOf(lines.size, start + EXCEPTION_SCAN_LINES)
        for (j in start + 1 until end) {
            val l = lines[j]
            if (WINEDBG_UNHANDLED.containsMatchIn(l) || WINE_UNHANDLED.containsMatchIn(l)) break
            EXCEPTION_CODE.find(l)?.let { m ->
                code = m.groupValues[1].lowercase()
                extra.add(j)
            }
            if (faultModule.isEmpty()) {
                FAULT_INSTRUCTION.find(l.trim())?.let { faultModule = it.groupValues[1].lowercase() }
            }
            if (BACKTRACE.containsMatchIn(l)) {
                if (frames.isNotEmpty()) break
                inBacktrace = true
                continue
            }
            if (inBacktrace) {
                val fm = FRAME.find(l)
                if (fm == null) {
                    if (frames.isNotEmpty()) inBacktrace = false
                    continue
                }
                if (frames.size < MAX_FRAMES) {
                    frames.add(parseFrame(fm.groupValues[2]))
                    if (frames.size == 1) extra.add(j)
                }
            }
        }
        val module = frames.firstOrNull()?.substringBefore('!')?.substringBefore('+')?.takeIf { it != "?" } ?: faultModule
        val component = if (module.isEmpty()) "wine" else componentForModule(module)
        return Hit(priority, start, component, "exception", code, module, frames, extra)
    }

    private fun parseFrame(rest: String): String {
        val mod = FRAME_MODULE.findAll(rest).lastOrNull()
        val module = mod?.groupValues?.get(1)?.lowercase() ?: "?"
        val symbol = FRAME_SYMBOL.find(rest.trim())
        return when {
            symbol != null -> "$module!${symbol.groupValues[1]}+0x${symbol.groupValues[2].lowercase()}"
            mod != null && mod.groupValues[2].isNotEmpty() -> "$module+0x${mod.groupValues[2].lowercase()}"
            else -> module
        }
    }

    private fun codeForDescription(desc: String): String {
        val lower = desc.lowercase()
        RAW_CODE.find(lower)?.let { return it.groupValues[1] }
        DESC_CODES.firstOrNull { lower.contains(it.first) }?.let { return it.second }
        return normalizeCode(desc)
    }

    private fun splitDllFunction(raw: String): Pair<String, String> {
        val dot = raw.lastIndexOf('.')
        if (dot <= 0) return raw.lowercase() to ""
        return raw.substring(0, dot).lowercase() to raw.substring(dot + 1)
    }

    private fun componentForModule(module: String): String {
        val base = module.lowercase().substringAfterLast('\\').substringAfterLast('/').removeSuffix(".dll").removeSuffix(".so")
        return when {
            base in DXVK_MODULES -> "dxvk"
            base in VKD3D_MODULES -> "vkd3d"
            base in FEX_MODULES -> "fex"
            base.startsWith("box64") -> "box64"
            base.startsWith("libvulkan_") || base.contains("freedreno") || base.contains("turnip") || base.contains("mesa") -> "mesa"
            base in WINE_MODULES -> "wine"
            else -> "game"
        }
    }

    private fun componentForPath(path: String): String {
        val lower = path.lowercase()
        return when {
            lower.contains("vkd3d") -> "vkd3d"
            lower.contains("dxvk") -> "dxvk"
            lower.contains("fex") -> "fex"
            lower.contains("box64") -> "box64"
            lower.contains("freedreno") || lower.contains("mesa") || lower.contains("/vulkan/") ||
                lower.contains("gallium") || lower.contains("zink") || lower.substringAfterLast('/').startsWith("tu_") -> "mesa"
            lower.contains("wine") || lower.contains("dlls/") -> "wine"
            else -> "game"
        }
    }

    private fun bytes(raw: String): String =
        Regex("""[0-9a-fA-F]{2}""").findAll(raw).map { it.value.uppercase() }.take(16).joinToString(" ")

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun quote(s: String): String = buildString {
        append('"')
        for (ch in s) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch < ' ' -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
        append('"')
    }
}
