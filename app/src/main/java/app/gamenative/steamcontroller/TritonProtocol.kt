package app.gamenative.steamcontroller

/**
 * Steam Controller (2026 "Triton") USB protocol: device identity, report decode, and the feature-report
 * payloads for init. Authored from this project's hardware-validated spec in docs/PUCK-PROTOCOL.md and
 * docs/HAPTICS-RESEARCH.md (reference: Valve's protocol as exposed in SDL's hidapi steam_triton driver,
 * Zlib). All multi-byte fields are little-endian; the wire report is [type, seq, buttons(u32), ...].
 */
object TritonProtocol {
    // ---- input report types (ETritonReportIDTypes), at buf[0] ----
    const val ID_STATE = 0x42
    const val ID_STATE_BLE = 0x45     // this unit streams 0x45; same layout as 0x42
    const val ID_STATE_TS = 0x47
    const val ID_BATTERY = 0x43
    const val ID_WIRELESS = 0x46
    const val ID_WIRELESS_X = 0x79

    // ---- feature-report (SET_REPORT) settings ----
    const val ID_SET_SETTINGS_VALUES = 0x87
    const val SETTING_LIZARD_MODE = 9
    const val LIZARD_MODE_OFF = 0
    const val SETTING_IMU_MODE = 48
    const val IMU_RAW_ACCEL = 0x08
    const val IMU_RAW_GYRO = 0x10

    // ---- TritonButtons bitmask (buttons u32) ----
    const val BTN_A = 0x00000001
    const val BTN_B = 0x00000002
    const val BTN_X = 0x00000004
    const val BTN_Y = 0x00000008
    const val BTN_QAM = 0x00000010          // Quick Access Menu (Steam "..." cluster)
    const val BTN_R3 = 0x00000020
    const val BTN_VIEW = 0x00000040
    const val BTN_R4 = 0x00000080
    const val BTN_R5 = 0x00000100
    const val BTN_RBUMPER = 0x00000200
    const val BTN_DPAD_DOWN = 0x00000400
    const val BTN_DPAD_RIGHT = 0x00000800
    const val BTN_DPAD_LEFT = 0x00001000
    const val BTN_DPAD_UP = 0x00002000
    const val BTN_MENU = 0x00004000
    const val BTN_L3 = 0x00008000
    const val BTN_STEAM = 0x00010000
    const val BTN_L4 = 0x00020000
    const val BTN_L5 = 0x00040000
    const val BTN_LBUMPER = 0x00080000
    const val BTN_RSTICK_TOUCH = 0x00100000
    const val BTN_RPAD_TOUCH = 0x00200000
    const val BTN_RPAD_CLICK = 0x00400000
    const val BTN_RTRIG_CLICK = 0x00800000
    const val BTN_LSTICK_TOUCH = 0x01000000
    const val BTN_LPAD_TOUCH = 0x02000000
    const val BTN_LPAD_CLICK = 0x04000000
    const val BTN_LTRIG_CLICK = 0x08000000
    const val BTN_RGRIP = 0x10000000
    const val BTN_LGRIP = 0x20000000

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun s16(b: ByteArray, o: Int): Int {
        val v = u16(b, o); return if (v >= 0x8000) v - 0x10000 else v
    }
    private fun u32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    /**
     * Decode a BLE GATT input-characteristic value. The BLE transport delivers the SAME `TritonMTUNoQuat`
     * payload but **without** the leading USB report-type byte, so seq is at offset 0 (buttons at offset 1).
     * Confirmed vs CollinKite/SteamControllerKit (docs/BLE-GATT-PATH.md).
     */
    fun decodeBleState(buf: ByteArray, len: Int): TritonState? {
        // Need indices 0..44 (gyroZ s16 at offset 43-44). BLE value arrays are exact-size, so guard >= 45.
        if (len < 45) return null
        return decodeFrom(buf, 0)  // payload (seq) starts at offset 0
    }

    /** Shared field decode. [p] = index of the seq byte (USB=1 after the type byte; BLE=0). */
    private fun decodeFrom(buf: ByteArray, p: Int): TritonState {
        val s = TritonState()
        s.buttons = u32(buf, p + 1)
        s.triggerLeft = s16(buf, p + 5)
        s.triggerRight = s16(buf, p + 7)
        s.leftStickX = s16(buf, p + 9); s.leftStickY = s16(buf, p + 11)
        s.rightStickX = s16(buf, p + 13); s.rightStickY = s16(buf, p + 15)
        s.leftPadX = s16(buf, p + 17); s.leftPadY = s16(buf, p + 19)
        s.rightPadX = s16(buf, p + 23); s.rightPadY = s16(buf, p + 25)
        // imu: u32 ts at p+29, then s16 accelX/Y/Z, s16 gyroX/Y/Z
        s.accelX = s16(buf, p + 33); s.accelY = s16(buf, p + 35); s.accelZ = s16(buf, p + 37)
        s.gyroX = s16(buf, p + 39); s.gyroY = s16(buf, p + 41); s.gyroZ = s16(buf, p + 43)
        return s
    }

    /**
     * BLE settings write (to the control characteristic 100f6c34): same FeatureReportMsg as USB but WITHOUT
     * the leading report-id byte (mirrors the input char, which drops the USB type prefix). Layout:
     * [type=0x87, length=3, settingNum, valueLo, valueHi].
     */
    private fun bleSetting(settingNum: Int, value: Int): ByteArray = byteArrayOf(
        ID_SET_SETTINGS_VALUES.toByte(), 3, settingNum.toByte(),
        (value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte(),
    )

    fun bleLizardOff(): ByteArray = bleSetting(SETTING_LIZARD_MODE, LIZARD_MODE_OFF)
    fun bleImuEnable(): ByteArray = bleSetting(SETTING_IMU_MODE, IMU_RAW_ACCEL or IMU_RAW_GYRO)
}

/** Decoded controller state. Sticks/pads/triggers are raw s16; gyro/accel raw s16 (scale in mapper). */
class TritonState {
    var buttons = 0
    var triggerLeft = 0
    var triggerRight = 0
    var leftStickX = 0
    var leftStickY = 0
    var rightStickX = 0
    var rightStickY = 0
    var leftPadX = 0
    var leftPadY = 0
    var rightPadX = 0
    var rightPadY = 0
    var accelX = 0
    var accelY = 0
    var accelZ = 0
    var gyroX = 0
    var gyroY = 0
    var gyroZ = 0

    fun has(bit: Int) = (buttons and bit) != 0
}
