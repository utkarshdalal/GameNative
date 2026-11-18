package app.gamenative.ui.util

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import app.gamenative.theme.sound.SoundPackManager
import app.gamenative.theme.sound.UiSound
import app.gamenative.theme.sound.UiSounds
import app.gamenative.ui.enums.DialogType

/**
 * App-side cue rules on top of [UiSounds]. Event cues (a menu, page, tab, launch, error) play at once.
 * Soft cues (button presses, dialogs) wait [SOFT_DELAY_MS] and give way to an event cue fired around them,
 * so one press makes one sound. Focus moves stay quiet for a moment after any other cue, which keeps the
 * initial focus of a screen or dialog silent. Nothing plays while a game owns the screen.
 */
object UiSoundCues {
    private const val SOFT_DELAY_MS = 60L
    private const val SOFT_YIELD_MS = 250L
    private const val NAVIGATE_QUIET_MS = 400L
    private const val REPEAT_MIN_MS = 45L

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile var gameRunning = false

    @Volatile private var lastCueAt = 0L

    @Volatile private var lastEventCueAt = 0L

    @Volatile private var lastRepeatAt = 0L

    @Volatile private var lastInputAt = 0L

    @Volatile private var foregroundAt = 0L

    @Volatile private var pendingSoft: Runnable? = null

    private val active: Boolean
        get() = !gameRunning && SoundPackManager.settings.value.enabled

    /** Non-reactive check for the composable hooks: with sounds off they compose nothing at all. */
    internal val hooksEnabled: Boolean
        get() = SoundPackManager.settings.value.enabled

    private fun now() = SystemClock.uptimeMillis()

    fun play(sound: UiSound) {
        if (!active) return
        val now = now()
        lastCueAt = now
        lastEventCueAt = now
        pendingSoft?.let { handler.removeCallbacks(it) }
        pendingSoft = null
        UiSounds.play(sound)
    }

    fun playSoft(sound: UiSound) {
        if (!active) return
        val scheduledAt = now()
        if (scheduledAt - lastEventCueAt < SOFT_YIELD_MS) return
        lastCueAt = scheduledAt
        val runnable = object : Runnable {
            override fun run() {
                if (pendingSoft === this) pendingSoft = null
                if (lastEventCueAt <= scheduledAt && active) UiSounds.play(sound)
            }
        }
        pendingSoft?.let { handler.removeCallbacks(it) }
        pendingSoft = runnable
        handler.postDelayed(runnable, SOFT_DELAY_MS)
    }

    fun launch() = play(UiSound.LAUNCH)

    fun notification() = play(UiSound.NOTIFY)

    fun toggle(on: Boolean) = play(if (on) UiSound.TOGGLE_ON else UiSound.TOGGLE_OFF)

    /** [up] is true when the value rose. */
    fun slider(up: Boolean) {
        if (!active) return
        repeatable(if (up) UiSound.SLIDER else UiSound.SLIDER_DOWN)
    }

    /**
     * Focus moves sound only once the controller or keyboard was used since the app started, so the focus the library
     * sets while it loads stays quiet. Key events in dialog windows do not reach the activity, so this checks for any
     * input since start rather than input just before the move.
     */
    fun navigate() {
        if (!active) return
        val now = now()
        if (lastInputAt < foregroundAt) return
        if (now - lastCueAt < NAVIGATE_QUIET_MS) return
        repeatable(UiSound.NAVIGATE)
    }

    fun quiet() {
        lastCueAt = now()
    }

    internal fun foreground() {
        val now = now()
        if (foregroundAt == 0L) foregroundAt = now
        lastCueAt = now
    }

    internal fun input() {
        lastInputAt = now()
    }

    private fun repeatable(sound: UiSound) {
        val now = now()
        if (now - lastRepeatAt < REPEAT_MIN_MS) return
        lastRepeatAt = now
        UiSounds.play(sound)
    }
}

private class LastValue<T>(var value: T)

private val ERROR_DIALOG_TYPES = setOf(
    DialogType.CRASH,
    DialogType.SYNC_FAIL,
    DialogType.NOT_ENOUGH_SPACE,
    DialogType.EXECUTABLE_NOT_FOUND,
)

/** Plays the cue [sound] picks for a change of [value] between compositions; never for the first composition. */
@Composable
fun <T> SoundOnChange(value: T, sound: (old: T, new: T) -> UiSound?) {
    if (UiSoundCues.hooksEnabled) {
        val last = remember { LastValue(value) }
        SideEffect {
            if (last.value != value) {
                val old = last.value
                last.value = value
                sound(old, value)?.let(UiSoundCues::play)
            }
        }
    }
}

@Composable
fun MenuSoundOnOpenChange(isOpen: Boolean) {
    SoundOnChange(isOpen) { _, open -> if (open) UiSound.MENU_OPEN else UiSound.MENU_CLOSE }
}

@Composable
fun PageSoundOnOpenChange(isOpen: Boolean) {
    SoundOnChange(isOpen) { _, open -> if (open) UiSound.PAGE_IN else UiSound.PAGE_OUT }
}

@Composable
fun <T> TabSoundOnChange(tab: T) {
    SoundOnChange(tab) { _, _ -> UiSound.TAB }
}

@Composable
fun ErrorSoundOnDialog(visible: Boolean, type: DialogType) {
    SoundOnChange(visible && type in ERROR_DIALOG_TYPES) { _, error -> if (error) UiSound.ERROR else null }
}

/** Call inside the visible branch of a dialog: it sounds on entering and leaving the composition. */
@Composable
fun DialogSound() {
    if (UiSoundCues.hooksEnabled) {
        DisposableEffect(Unit) {
            UiSoundCues.playSoft(UiSound.DIALOG_OPEN)
            onDispose { UiSoundCues.playSoft(UiSound.DIALOG_CLOSE) }
        }
    }
}

/** Plays NAVIGATE when [focused] turns true under controller or keyboard input. */
@Composable
fun NavigateSoundOnFocusGain(focused: Boolean) {
    if (UiSoundCues.hooksEnabled) {
        val inputModeManager = LocalInputModeManager.current
        val last = remember { LastValue(focused) }
        SideEffect {
            if (last.value != focused) {
                last.value = focused
                if (focused && inputModeManager.inputMode == InputMode.Keyboard) UiSoundCues.navigate()
            }
        }
    }
}

/**
 * Root hook: tells the sound backend whether the app UI is in the foreground and whether a game owns the
 * screen, and plays the soft SELECT / BACK cues for confirm and back buttons pressed in the app window.
 */
@Composable
fun UiSoundLifecycle(gameRunning: Boolean) {
    SideEffect {
        if (UiSoundCues.gameRunning != gameRunning) {
            UiSoundCues.gameRunning = gameRunning
            if (!gameRunning) UiSoundCues.quiet()
            SoundPackManager.setGameRunning(gameRunning)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    UiSoundCues.foreground()
                    SoundPackManager.setUiForeground(true)
                }
                Lifecycle.Event.ON_STOP -> SoundPackManager.setUiForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            SoundPackManager.setUiForeground(false)
        }
    }

    // The input listeners exist only while sounds are on, so with sounds off no key or motion event reaches this code.
    val soundsOn = SoundPackManager.settings.collectAsState().value.enabled
    if (soundsOn) {
        DisposableEffect(Unit) {
            val onKey: (AndroidEvent.KeyEvent) -> Boolean = { androidEvent ->
                val event = androidEvent.event
                if (event.action == KeyEvent.ACTION_DOWN) UiSoundCues.input()
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_BUTTON_A,
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        -> UiSoundCues.playSoft(UiSound.SELECT)

                        KeyEvent.KEYCODE_BUTTON_B,
                        KeyEvent.KEYCODE_BACK,
                        -> UiSoundCues.playSoft(UiSound.BACK)
                    }
                }
                false
            }
            val onMotion: (AndroidEvent.MotionEvent) -> Boolean = { androidEvent ->
                val event = androidEvent.event
                if (event != null && event.isFromSource(InputDevice.SOURCE_JOYSTICK)) UiSoundCues.input()
                false
            }
            PluviaApp.events.on<AndroidEvent.KeyEvent, Boolean>(onKey)
            PluviaApp.events.on<AndroidEvent.MotionEvent, Boolean>(onMotion)
            onDispose {
                PluviaApp.events.off<AndroidEvent.KeyEvent, Boolean>(onKey)
                PluviaApp.events.off<AndroidEvent.MotionEvent, Boolean>(onMotion)
            }
        }
    }
}
