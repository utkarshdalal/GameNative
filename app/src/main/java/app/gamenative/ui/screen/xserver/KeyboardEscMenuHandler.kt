package app.gamenative.ui.screen.xserver

import android.os.SystemClock
import android.view.KeyEvent
import com.winlator.xserver.Keyboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val KEYBOARD_ESC_MENU_HOLD_MS = 2_000L

internal class KeyboardEscMenuHandler(
    private val scope: CoroutineScope,
) {
    private var menuJob: Job? = null
    private var menuTriggered = false

    fun isEsc(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_ESCAPE

    fun cancel() {
        menuJob?.cancel()
        menuJob = null
    }

    fun handleOverlayEsc(event: KeyEvent, keyboard: Keyboard?) {
        if (event.action == KeyEvent.ACTION_UP && menuJob != null && !menuTriggered) {
            dispatchEscToGame(event, keyboard)
        }
        cancel()
        menuTriggered = false
    }

    fun handleGameEsc(
        event: KeyEvent,
        keyboard: Keyboard?,
        canOpenMenu: () -> Boolean,
        openMenu: () -> Unit,
    ): Boolean {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    menuTriggered = false
                    cancel()
                    menuJob = scope.launch {
                        delay(KEYBOARD_ESC_MENU_HOLD_MS)
                        if (canOpenMenu()) {
                            menuTriggered = true
                            menuJob = null
                            dispatchEscToGame(event.asEscEvent(action = KeyEvent.ACTION_UP), keyboard)
                            openMenu()
                        }
                    }
                }
                dispatchEscToGame(event, keyboard)
            }
            KeyEvent.ACTION_UP -> {
                val shouldReleaseGameEsc = menuJob != null && !menuTriggered
                val shouldConsume = menuJob != null || menuTriggered

                cancel()
                if (shouldReleaseGameEsc) {
                    dispatchEscToGame(event, keyboard)
                } else {
                    menuTriggered = false
                    shouldConsume
                }
            }
            else -> dispatchEscToGame(event, keyboard)
        }
    }

    private fun dispatchEscToGame(event: KeyEvent, keyboard: Keyboard?): Boolean {
        val gameEscEvent = event.asEscEvent()
        val targetKeyboard = keyboard ?: return false
        return if (gameEscEvent.device?.isVirtual == true) {
            targetKeyboard.onVirtualKeyEvent(gameEscEvent)
        } else {
            targetKeyboard.onKeyEvent(gameEscEvent)
        }
    }

    private fun KeyEvent.asEscEvent(
        action: Int = this.action,
        repeatCount: Int = this.repeatCount,
    ): KeyEvent {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && action == this.action && repeatCount == this.repeatCount) {
            return this
        }

        val eventTime = if (action == this.action) eventTime else SystemClock.uptimeMillis()
        return KeyEvent(
            downTime,
            eventTime,
            action,
            KeyEvent.KEYCODE_ESCAPE,
            repeatCount,
            metaState,
            deviceId,
            scanCode,
            flags,
            source,
        )
    }
}
