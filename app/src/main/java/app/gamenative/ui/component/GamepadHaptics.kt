package app.gamenative.ui.component

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.InputDevice

/**
 * Lightweight haptics for gamepad-driven UI actions (D4,
 * docs/superpowers/specs/2026-08-08-gamepad-input-refactoring-design.md).
 *
 * Only vibrates when a physical gamepad is connected (touch users get no buzz) and
 * respects the system's vibration settings. Short pulses: subtle on activation,
 * shorter on back.
 */
object GamepadHaptics {
    fun isGamepadConnected(): Boolean = InputDevice.getDeviceIds().any { id ->
        val device = InputDevice.getDevice(id)
        device != null && (device.sources and InputDevice.SOURCE_GAMEPAD) != 0
    }

    fun vibrate(context: Context, durationMs: Long = 18L) {
        if (!isGamepadConnected()) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(Vibrator::class.java)
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE),
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }
}
