// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox.live

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64

/** One `<Component>` of the deviceaddcredential.srf DeviceInfo. */
data class DeviceComponent(val name: Int, val value: String?, val error: String?) {
    companion object {
        private const val NOT_FOUND = "-2147024894"

        fun value(name: Int, value: String) = DeviceComponent(name, value, null)

        fun error(name: Int) = DeviceComponent(name, null, NOT_FOUND)
    }
}

/**
 * Hardware description for device registration. No real identifiers are read: the SMBIOS system record
 * (type 1) is synthesized from [seed], so the same seed always yields the same machine.
 */
object SyntheticHardware {
    private const val MANUFACTURER = "System manufacturer"
    private const val PRODUCT = "System Product Name"
    private const val VERSION = "System Version"
    private const val SKU = "SKU"
    private const val FAMILY = "To be filled by O.E.M."
    private const val SERIAL_CHARS = "0123456789ABCDEFGHJKLMNPRSTUVWXYZ"

    class Smbios(val raw: ByteArray, val version: ByteArray, val serial: ByteArray, val uuid: ByteArray)

    /** Seed kept implicitly with the device record: its random account name and password. */
    fun seedFor(username: String, password: String): ByteArray =
        LiveCrypto.sha256("xbox-device-hardware\u0000$username\u0000$password".toByteArray(Charsets.UTF_8))

    private fun stream(seed: ByteArray, size: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var counter = 0
        while (out.size() < size) {
            out.write(LiveCrypto.sha256(seed + ByteBuffer.allocate(4).putInt(counter++).array()))
        }
        return out.toByteArray().copyOf(size)
    }

    fun smbios(seed: ByteArray): Smbios {
        val bytes = stream(seed, 32)
        val uuid = bytes.copyOfRange(0, 16).also {
            it[7] = ((it[7].toInt() and 0x0f) or 0x40).toByte()
            it[8] = ((it[8].toInt() and 0x3f) or 0x80).toByte()
        }
        val serial = String(CharArray(12) { SERIAL_CHARS[(bytes[16 + it].toInt() and 0xff) % SERIAL_CHARS.length] })
        val strings = listOf(MANUFACTURER, PRODUCT, VERSION, serial, SKU, FAMILY)
        val raw = ByteArrayOutputStream()
        raw.write(byteArrayOf(1, 0x1b, 1, 0, 1, 2, 3, 4))
        raw.write(uuid)
        raw.write(byteArrayOf(6, 5, 6))
        for (s in strings) {
            raw.write(s.toByteArray(Charsets.US_ASCII))
            raw.write(0)
        }
        raw.write(0)
        return Smbios(raw.toByteArray(), VERSION.toByteArray(Charsets.US_ASCII), serial.toByteArray(Charsets.US_ASCII), uuid)
    }

    /** Components in the order and shape the reference client sends. */
    fun components(seed: ByteArray): List<DeviceComponent> {
        val smbios = smbios(seed)
        val smbiosBuffer = smbios.raw.copyOf(256)
        val diskBuffer = ByteArray(64)
        val (v2, v4) = ClepChallenge.licenseChallenge(smbiosBuffer, diskBuffer)
        val b64 = Base64.getEncoder()
        return listOf(
            DeviceComponent.value(4113, "AA=="),
            DeviceComponent.error(4101),
            DeviceComponent.value(8196, b64.encodeToString(v2)),
            DeviceComponent.value(8197, b64.encodeToString(v4)),
            DeviceComponent.value(4100, b64.encodeToString(smbios.version)),
            DeviceComponent.value(4101, b64.encodeToString(smbios.serial)),
            DeviceComponent.value(4102, b64.encodeToString(smbios.uuid)),
            DeviceComponent.value(4145, "AQAAAA=="),
            DeviceComponent.error(4160),
            DeviceComponent.error(4161),
        )
    }
}

/** Result of deviceaddcredential.srf. */
data class DeviceAddResult(val puid: String, val hwDeviceId: String, val deviceId: String, val spLicenseBlock: String)

object DeviceCredential {
    fun requestXml(cfg: LiveConfig, membername: String, password: String, components: List<DeviceComponent>): String {
        val root = XElement("", "DeviceAddRequest").apply {
            el("", "ClientInfo", "") {
                attr("name", cfg.clientName)
                attr("version", cfg.clientVersion)
                el("", "BinaryVersion", "") { text(cfg.binaryVersion) }
            }
            el("", "Authentication", "") {
                el("", "Membername", "") { text(membername) }
                el("", "Password", "") { text(password) }
            }
            el("", "DeviceInfo", "") {
                attr("Id", "DeviceInfo")
                for (c in components) {
                    el("", "Component", "") {
                        attr("name", c.name.toString())
                        attr("error", c.error ?: "")
                        c.value?.let { text(it) }
                    }
                }
            }
        }
        return LiveXml.serialize(root)
    }

    fun parseResponse(xml: String): DeviceAddResult {
        val root = LiveXml.parse(xml, namespaceAware = false)
        if (root.localName != "DeviceAddResponse" || root.attribute("Success") != "true") {
            throw LiveException("Device registration failed: ${xml.take(300)}")
        }
        val license = root.child("License") ?: throw LiveException("Device registration returned no License")
        return DeviceAddResult(
            puid = root.childText("puid") ?: throw LiveException("Device registration returned no puid"),
            hwDeviceId = root.childText("HWDeviceID") ?: throw LiveException("Device registration returned no HWDeviceID"),
            deviceId = license.path("Binding", "DeviceId")?.textContent() ?: "",
            spLicenseBlock = license.childText("SPLicenseBlock")
                ?: throw LiveException("Device registration returned no SPLicenseBlock"),
        )
    }
}
