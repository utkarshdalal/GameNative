package app.gamenative.steamcontroller

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Byte layout of the Triton rumble output report (SDL MsgHapticRumble, id 0x80, packed LE). */
class HapticsRumbleTest {
    @Test fun rumbleReport_packsMotorsLittleEndian() {
        val r = TritonHaptics.rumbleReport(0x1234, 0xABCD)
        assertEquals("10-byte report", 10, r.size)
        assertArrayEquals(
            byteArrayOf(
                0x80.toByte(),                 // id
                0,                             // type
                0, 0,                          // intensity
                0x34, 0x12,                    // left.speed (low-freq motor), LE
                0,                             // left.gain
                0xCD.toByte(), 0xAB.toByte(),  // right.speed (high-freq motor), LE
                0,                             // right.gain
            ),
            r,
        )
    }

    @Test fun rumbleReport_zeroIsAllZeroExceptId() {
        val r = TritonHaptics.rumbleReport(0, 0)
        assertEquals(0x80.toByte(), r[0])
        for (i in 1 until r.size) assertEquals("byte $i", 0.toByte(), r[i])
    }

    @Test fun rumbleReport_fullScaleClampsToTwoBytes() {
        val r = TritonHaptics.rumbleReport(0xFFFF, 0xFFFF)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), r.copyOfRange(4, 6))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), r.copyOfRange(7, 9))
    }

    // ---- update()/handlePad(): the stateful click + slide-detent path (right pad) ----
    private val RTOUCH = TritonProtocol.BTN_RPAD_TOUCH
    private val RCLICK = TritonProtocol.BTN_RPAD_CLICK
    private fun rState(buttons: Int, x: Int = 0, y: Int = 0) =
        TritonState().apply { this.buttons = buttons; rightPadX = x; rightPadY = y }
    private fun List<ByteArray>.count(cmd: Int) =
        count { it.size >= 3 && (it[0].toInt() and 0xFF) == TritonHaptics.ID_OUT_HAPTIC_COMMAND && it[2].toInt() == cmd }

    @Test fun `fresh right-pad click fires exactly one click`() {
        val out = mutableListOf<ByteArray>()
        TritonHaptics { out.add(it) }.update(rState(RTOUCH or RCLICK, 100, 100), prevButtons = 0, HapticSettings())
        assertEquals(1, out.count(TritonHaptics.CMD_CLICK))
        assertEquals(0, out.count(TritonHaptics.CMD_TICK)) // touch-down alone doesn't tick
    }

    @Test fun `sliding past detentStep fires a detent tick`() {
        val out = mutableListOf<ByteArray>()
        val h = TritonHaptics { out.add(it) }
        val cfg = HapticSettings()
        h.update(rState(RTOUCH, 0, 0), prevButtons = 0, cfg)                    // touch down (accum reset)
        h.update(rState(RTOUCH, cfg.detentStep, 0), prevButtons = RTOUCH, cfg)  // slide one detent's worth
        assertEquals(1, out.count(TritonHaptics.CMD_TICK))
    }

    @Test fun `jitter below moveNoise fires no tick`() {
        val out = mutableListOf<ByteArray>()
        val h = TritonHaptics { out.add(it) }
        val cfg = HapticSettings()
        h.update(rState(RTOUCH, 0, 0), prevButtons = 0, cfg)
        // Feed enough sub-noise steps that their sum crosses detentStep — if the filter accumulated jitter
        // instead of rejecting it, this would fire a tick. Each per-report delta stays below moveNoise.
        val step = cfg.moveNoise - 1
        for (i in 1..(cfg.detentStep / step) + 1) h.update(rState(RTOUCH, i * step, 0), prevButtons = RTOUCH, cfg)
        assertEquals(0, out.count(TritonHaptics.CMD_TICK))
    }
}
