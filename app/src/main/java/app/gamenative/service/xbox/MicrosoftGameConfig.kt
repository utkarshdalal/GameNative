package app.gamenative.service.xbox

import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** The identity and executables a GDK package declares in MicrosoftGame.config at its root. */
data class MicrosoftGameConfig(
    val name: String,
    val publisher: String,
    val version: String,
    val titleId: String?,
    val storeId: String?,
    val msaAppId: String?,
    val executables: List<Executable>,
) {
    data class Executable(val name: String, val id: String?, val targetDeviceFamily: String?, val overrideDisplayName: String?)

    val packageFamilyName: String get() = "${name}_${publisherHash(publisher)}"

    /** Packaged desktop games are x64 with an empty resource id. */
    val packageFullName: String get() = "${name}_${version}_x64__${publisherHash(publisher)}"

    /** The executable a PC launch starts: the first one targeting PC, else the first listed. */
    val pcExecutable: Executable? get() =
        executables.firstOrNull { it.targetDeviceFamily.equals("PC", true) } ?: executables.firstOrNull()

    companion object {
        const val FILE_NAME = "MicrosoftGame.config"

        fun find(installDir: File): File? =
            installDir.listFiles().orEmpty().firstOrNull { it.isFile && it.name.equals(FILE_NAME, true) }

        fun read(installDir: File): MicrosoftGameConfig? = find(installDir)?.let { parse(it.readBytes()) }

        fun parse(bytes: ByteArray): MicrosoftGameConfig? = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val root = factory.newDocumentBuilder().parse(bytes.inputStream()).documentElement
            val identity = child(root, "Identity") ?: return null
            val executables = child(root, "ExecutableList")?.let { list ->
                children(list, "Executable").map {
                    Executable(
                        name = it.getAttribute("Name"),
                        id = it.getAttribute("Id").ifEmpty { null },
                        targetDeviceFamily = it.getAttribute("TargetDeviceFamily").ifEmpty { null },
                        overrideDisplayName = it.getAttribute("OverrideDisplayName").ifEmpty { null },
                    )
                }.filter { it.name.isNotEmpty() }
            }.orEmpty()
            MicrosoftGameConfig(
                name = identity.getAttribute("Name"),
                publisher = identity.getAttribute("Publisher"),
                version = identity.getAttribute("Version"),
                titleId = child(root, "TitleId")?.textContent?.trim()?.ifEmpty { null },
                storeId = child(root, "StoreId")?.textContent?.trim()?.ifEmpty { null },
                msaAppId = child(root, "MSAAppId")?.textContent?.trim()?.ifEmpty { null },
                executables = executables,
            ).takeIf { it.name.isNotEmpty() && it.publisher.isNotEmpty() }
        }.getOrNull()

        private fun children(parent: Element, name: String): List<Element> {
            val nodes = parent.childNodes
            return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.tagName.equals(name, true) }
        }

        private fun child(parent: Element, name: String): Element? = children(parent, name).firstOrNull()

        /**
         * Package publisher id: SHA-256 of the UTF-16LE publisher string, first 8 bytes,
         * written as 13 five-bit digits of the Crockford-style alphabet Windows uses.
         */
        fun publisherHash(publisher: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(publisher.toByteArray(Charsets.UTF_16LE))
            var bits = 0L
            for (i in 0 until 8) bits = (bits shl 8) or (digest[i].toLong() and 0xFF)
            val alphabet = "0123456789abcdefghjkmnpqrstvwxyz"
            val out = StringBuilder()
            for (i in 0 until 13) {
                val shift = 64 - 5 * (i + 1)
                val index = if (shift >= 0) (bits ushr shift) and 0x1F else (bits shl -shift) and 0x1F
                out.append(alphabet[index.toInt()])
            }
            return out.toString()
        }
    }
}
