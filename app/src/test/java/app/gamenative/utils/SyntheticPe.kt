package app.gamenative.utils

import java.io.ByteArrayOutputStream

internal object SyntheticPe {
    private const val SECTION_RVA = 0x1000
    private const val SECTION_FILE_OFFSET = 0x200
    private const val VERSION_OFFSET = 0x60

    fun build(
        machine: Int,
        version: IntArray? = null,
        strings: Map<String, String> = emptyMap(),
        hybrid: Boolean = false,
    ): ByteArray {
        val pe32Plus = machine == PeVersionReader.MACHINE_AMD64 || machine == 0xAA64
        val versionData = version?.let { versionInfo(it, strings) }
        val section = ByteArrayOutputStream()
        var loadConfigOffset = 0
        if (versionData != null) {
            section.write(directory(RT_VERSION, 0x18 or SUBDIRECTORY))
            section.write(directory(1, 0x30 or SUBDIRECTORY))
            section.write(directory(0x409, 0x48))
            section.write(le32(SECTION_RVA + VERSION_OFFSET) + le32(versionData.size) + ByteArray(8))
            section.write(ByteArray(VERSION_OFFSET - section.size()))
            section.write(versionData)
        }
        if (pe32Plus) {
            section.write(ByteArray((8 - section.size() % 8) % 8))
            loadConfigOffset = section.size()
            val loadConfig = ByteArray(0x100)
            le32(loadConfig.size).copyInto(loadConfig, 0)
            if (hybrid) le32(0x2000).copyInto(loadConfig, 0xC8)
            section.write(loadConfig)
        }
        val sectionBytes = section.toByteArray()

        val optionalSize = if (pe32Plus) 240 else 224
        val directories = if (pe32Plus) 112 else 96
        val optional = ByteArray(optionalSize)
        le16(if (pe32Plus) 0x20B else 0x10B).copyInto(optional, 0)
        le32(16).copyInto(optional, directories - 4)
        if (versionData != null) {
            le32(SECTION_RVA).copyInto(optional, directories + 2 * 8)
            le32(sectionBytes.size).copyInto(optional, directories + 2 * 8 + 4)
        }
        if (pe32Plus) {
            le32(SECTION_RVA + loadConfigOffset).copyInto(optional, directories + 10 * 8)
            le32(0x100).copyInto(optional, directories + 10 * 8 + 4)
        }

        val sectionHeader = ByteArray(40)
        ".rsrc".toByteArray(Charsets.US_ASCII).copyInto(sectionHeader, 0)
        le32(sectionBytes.size).copyInto(sectionHeader, 8)
        le32(SECTION_RVA).copyInto(sectionHeader, 12)
        le32(sectionBytes.size).copyInto(sectionHeader, 16)
        le32(SECTION_FILE_OFFSET).copyInto(sectionHeader, 20)

        val dos = ByteArray(0x40)
        dos[0] = 'M'.code.toByte()
        dos[1] = 'Z'.code.toByte()
        le32(0x40).copyInto(dos, 0x3C)
        val coff = ByteArray(24)
        "PE".toByteArray(Charsets.US_ASCII).copyInto(coff, 0)
        le16(machine).copyInto(coff, 4)
        le16(1).copyInto(coff, 6)
        le16(optionalSize).copyInto(coff, 20)

        val headers = dos + coff + optional + sectionHeader
        return headers + ByteArray(SECTION_FILE_OFFSET - headers.size) + sectionBytes
    }

    private const val RT_VERSION = 16
    private const val SUBDIRECTORY = 0x80000000.toInt()

    private fun directory(id: Int, target: Int): ByteArray =
        ByteArray(12) + le16(0) + le16(1) + le32(id) + le32(target)

    private fun versionInfo(version: IntArray, strings: Map<String, String>): ByteArray {
        val fixed = le32(0xFEEF04BD.toInt()) + le32(0x10000) +
            le32((version[0] shl 16) or version[1]) + le32((version[2] shl 16) or version[3]) +
            le32((version[0] shl 16) or version[1]) + le32((version[2] shl 16) or version[3]) +
            ByteArray(28)
        val stringBlocks = strings.map { (key, value) ->
            block(key, utf16z(value), value.length + 1, 1, emptyList())
        }
        val table = block("040904b0", ByteArray(0), 0, 1, stringBlocks)
        val stringFileInfo = block("StringFileInfo", ByteArray(0), 0, 1, listOf(table))
        return block("VS_VERSION_INFO", fixed, fixed.size, 0, listOf(stringFileInfo))
    }

    private fun block(key: String, value: ByteArray, valueLength: Int, type: Int, children: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteArray(6))
        out.write(utf16z(key))
        pad(out)
        out.write(value)
        for (child in children) {
            pad(out)
            out.write(child)
        }
        val bytes = out.toByteArray()
        le16(bytes.size).copyInto(bytes, 0)
        le16(valueLength).copyInto(bytes, 2)
        le16(type).copyInto(bytes, 4)
        return bytes
    }

    private fun pad(out: ByteArrayOutputStream) {
        while (out.size() % 4 != 0) out.write(0)
    }

    private fun utf16z(text: String): ByteArray = (text + "\u0000").toByteArray(Charsets.UTF_16LE)

    private fun le16(value: Int): ByteArray = byteArrayOf(value.toByte(), (value ushr 8).toByte())

    private fun le32(value: Int): ByteArray =
        byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())
}
