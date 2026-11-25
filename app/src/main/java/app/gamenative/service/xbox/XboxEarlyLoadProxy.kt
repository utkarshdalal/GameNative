package app.gamenative.service.xbox

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Loads the runtime stand-in before the game's entry point, so package identity answers are in place for
 * appmodel calls the game makes before XGameRuntimeInitialize. A DLL that the game executable imports and that
 * ships in the game folder is renamed to `<name>_real.dll`; the proxy template takes its name, with an export
 * table of forwarders to the renamed file (same ordinals and names) written into the template's reserved
 * section. The proxy's only code loads xgameruntime.dll from its folder at process attach.
 */
object XboxEarlyLoadProxy {
    const val TEMPLATE = "xboxproxy.dll"
    private const val SECTION = ".gnexp"
    private const val MARKER = ".gamenative-xbox-proxy"

    /** Imports never proxied: our own files and anything the runtime itself loads. */
    private val excluded = setOf("xgameruntime.dll", TEMPLATE)

    /** Game-provided DLLs worth preferring: GDK extension libraries load with the executable. */
    private val preferred = listOf("xcurl.dll", "libhttpclient.gdk.dll", "party.dll", "partyxboxlive.dll", "playfabcore.gdk.dll")

    data class Proxied(val original: String, val real: String)

    /** Picks a target, renames it and writes the proxy. Returns null when the executable imports no game-folder DLL. */
    fun install(exeDir: File, exe: File, template: File): Proxied? {
        current(exeDir)?.let { if (File(exeDir, it.real).isFile && File(exeDir, it.original).isFile) return it }
        val imports = PeFile(exe.readBytes()).importedDlls().map { it.lowercase() }
        val folder = exeDir.listFiles().orEmpty().filter { it.isFile }.associateBy { it.name.lowercase() }
        val candidates = imports.filter { it in folder && it !in excluded && !it.endsWith("_real.dll") }
        val pick = candidates.sortedBy { preferred.indexOf(it).let { i -> if (i < 0) preferred.size else i } }.firstOrNull()
            ?: return null
        val original = folder.getValue(pick)
        val realName = original.nameWithoutExtension + "_real.dll"
        val real = File(exeDir, realName)
        val proxyBytes = buildProxy(template.readBytes(), PeFile(original.readBytes()).exports(), realName.removeSuffix(".dll"))
        check(original.renameTo(real)) { "Cannot rename ${original.name}" }
        File(exeDir, original.name).writeBytes(proxyBytes)
        File(exeDir, MARKER).writeText("${original.name}\n$realName\n")
        return Proxied(original.name, realName)
    }

    /** Puts the game's own DLL back, for uninstall, update or when the user turns the proxy off. */
    fun restore(exeDir: File) {
        val p = current(exeDir) ?: return
        val real = File(exeDir, p.real)
        if (real.isFile) {
            File(exeDir, p.original).delete()
            check(real.renameTo(File(exeDir, p.original))) { "Cannot restore ${p.original}" }
        }
        File(exeDir, MARKER).delete()
    }

    fun current(exeDir: File): Proxied? {
        val lines = File(exeDir, MARKER).takeIf { it.isFile }?.readLines()?.map { it.trim() } ?: return null
        if (lines.size < 2 || lines.any { it.contains('/') || it.contains('\\') || it.contains("..") }) return null
        return Proxied(lines[0], lines[1])
    }

    data class Exports(val dllName: String, val base: Int, val functionCount: Int, val names: Map<Int, String>, val live: Set<Int>)

    /** Writes an export directory whose every entry forwards to `[forwardModule].<name>` or `[forwardModule].#<ordinal>`. */
    fun buildProxy(template: ByteArray, real: Exports, forwardModule: String): ByteArray {
        val out = template.copyOf()
        val pe = PeFile(out)
        val section = pe.sections.firstOrNull { it.name == SECTION } ?: error("Proxy template has no $SECTION section")
        val buf = ByteBuffer.allocate(section.rawSize).order(ByteOrder.LITTLE_ENDIAN)
        val rva0 = section.virtualAddress
        val n = real.functionCount
        val named = real.names.entries.sortedBy { it.value }
        val dirSize = 40
        val eatAt = dirSize
        val namePtrAt = eatAt + 4 * n
        val ordAt = namePtrAt + 4 * named.size
        var strAt = ordAt + 2 * named.size
        fun putString(s: String): Int {
            val bytes = s.toByteArray(Charsets.US_ASCII)
            check(strAt + bytes.size + 1 <= buf.capacity()) { "Export table does not fit the proxy section" }
            val at = strAt
            buf.position(at); buf.put(bytes); buf.put(0)
            strAt += bytes.size + 1
            return rva0 + at
        }
        check(strAt <= buf.capacity()) { "Export table does not fit the proxy section" }
        val dllNameRva = putString(real.dllName)
        for (i in 0 until n) {
            if (i !in real.live) continue
            val target = real.names[i]?.let { "$forwardModule.$it" } ?: "$forwardModule.#${real.base + i}"
            buf.putInt(eatAt + 4 * i, putString(target))
        }
        named.forEachIndexed { k, (index, name) ->
            buf.putInt(namePtrAt + 4 * k, putString(name))
            buf.putShort(ordAt + 2 * k, index.toShort())
        }
        buf.putInt(12, dllNameRva)
        buf.putInt(16, real.base)
        buf.putInt(20, n)
        buf.putInt(24, named.size)
        buf.putInt(28, rva0 + eatAt)
        buf.putInt(32, rva0 + namePtrAt)
        buf.putInt(36, rva0 + ordAt)
        System.arraycopy(buf.array(), 0, out, section.rawOffset, section.rawSize)
        pe.setDataDirectory(out, 0, rva0, strAt)
        return out
    }

    internal class PeFile(private val d: ByteArray) {
        class Section(val name: String, val virtualAddress: Int, val virtualSize: Int, val rawOffset: Int, val rawSize: Int)

        private fun u16(at: Int) = (d[at].toInt() and 0xFF) or ((d[at + 1].toInt() and 0xFF) shl 8)
        private fun u32(at: Int) = u16(at) or (u16(at + 2) shl 16)

        private val peAt = u32(0x3C).also { require(u16(0) == 0x5A4D && u32(it) == 0x4550) { "Not a PE file" } }
        private val optAt = peAt + 24
        private val is64 = u16(optAt) == 0x20B
        private val dataDirAt = optAt + if (is64) 112 else 96

        val sections: List<Section> = run {
            val count = u16(peAt + 6)
            val first = optAt + u16(peAt + 20)
            (0 until count).map { i ->
                val at = first + 40 * i
                val name = String(d, at, 8, Charsets.US_ASCII).trimEnd('\u0000')
                Section(name, u32(at + 12), u32(at + 8), u32(at + 20), u32(at + 16))
            }
        }

        private fun offset(rva: Int): Int {
            val s = sections.firstOrNull { rva >= it.virtualAddress && rva < it.virtualAddress + maxOf(it.virtualSize, it.rawSize) }
                ?: error("RVA outside every section")
            return rva - s.virtualAddress + s.rawOffset
        }

        private fun cString(rva: Int): String {
            var at = offset(rva)
            val start = at
            while (d[at] != 0.toByte()) at++
            return String(d, start, at - start, Charsets.US_ASCII)
        }

        private fun dataDirectory(index: Int): Pair<Int, Int> = u32(dataDirAt + 8 * index) to u32(dataDirAt + 8 * index + 4)

        fun setDataDirectory(target: ByteArray, index: Int, rva: Int, size: Int) {
            val at = dataDirAt + 8 * index
            for (i in 0..3) target[at + i] = (rva ushr (8 * i)).toByte()
            for (i in 0..3) target[at + 4 + i] = (size ushr (8 * i)).toByte()
        }

        fun importedDlls(): List<String> {
            val (rva, _) = dataDirectory(1)
            if (rva == 0) return emptyList()
            val names = mutableListOf<String>()
            var at = offset(rva)
            while (u32(at + 12) != 0) {
                names += cString(u32(at + 12))
                at += 20
            }
            return names
        }

        fun exports(): Exports {
            val (rva, _) = dataDirectory(0)
            require(rva != 0) { "DLL has no exports" }
            val at = offset(rva)
            val base = u32(at + 16)
            val functions = u32(at + 20)
            val nameCount = u32(at + 24)
            val eat = offset(u32(at + 28))
            val namePtrs = offset(u32(at + 32))
            val ordinals = offset(u32(at + 36))
            val names = (0 until nameCount).associate { k -> u16(ordinals + 2 * k) to cString(u32(namePtrs + 4 * k)) }
            val live = (0 until functions).filter { u32(eat + 4 * it) != 0 }.toSet()
            return Exports(cString(u32(at + 12)), base, functions, names, live)
        }
    }
}
