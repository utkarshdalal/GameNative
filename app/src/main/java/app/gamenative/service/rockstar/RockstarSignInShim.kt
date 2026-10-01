package app.gamenative.service.rockstar

import java.io.File
import org.json.JSONObject

/**
 * The bridge script the Rockstar sign-in page needs is shipped in the Rockstar helper archive
 * and filled in here. Placeholders: @FP@ (device fingerprint JSON), @TITLE@ (ROS title name),
 * @BRIDGE@ (the name of the object exposed with addJavascriptInterface).
 */
object RockstarSignInShim {

    fun fingerprint(deviceName: String = "GAMENATIVE"): JSONObject = JSONObject()
        .put("device_name", deviceName)
        .put("machine_name", deviceName)
        .put("os_version", "10.0.19045")
        .put("volume_serial", "1a2b3c4d")
        .put("cpu_info", "178bfbff")

    fun script(filesDir: File, titleName: String, bridge: String, deviceName: String = "GAMENATIVE"): String =
        fill(template(filesDir), fingerprint(deviceName).toString(), titleName, bridge)

    internal fun fill(template: String, fingerprintJson: String, titleName: String, bridge: String): String =
        template.replace("@FP@", fingerprintJson).replace("@TITLE@", titleName).replace("@BRIDGE@", bridge)

    private fun template(filesDir: File): String {
        val file = File(RockstarHelperArchive.directory(filesDir), RockstarHelperArchive.SIGNIN_SHIM)
        check(file.isFile) { "Rockstar helper archive is not installed" }
        return file.readText()
    }
}
