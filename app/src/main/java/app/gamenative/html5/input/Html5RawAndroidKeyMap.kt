package app.gamenative.html5.input

import com.winlator.inputcontrols.Binding

// raw Android KeyEvent.keyCode → Binding fallback. only consulted when the active profile has
// no controller for a given device -- defensive catch for the theoretically-impossible
// "no wildcard" case. otherwise the wildcard "*" controller from
// Html5DefaultControlsProfileFactory.populateWithGamepadBindings handles every keycode.
object Html5RawAndroidKeyMap {
    val ANDROID_TO_BINDING: Map<Int, Binding> = mapOf(
        android.view.KeyEvent.KEYCODE_DPAD_UP to Binding.KEY_UP,
        android.view.KeyEvent.KEYCODE_DPAD_DOWN to Binding.KEY_DOWN,
        android.view.KeyEvent.KEYCODE_DPAD_LEFT to Binding.KEY_LEFT,
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT to Binding.KEY_RIGHT,
    )

    fun bindingFor(androidKeyCode: Int): Binding? = ANDROID_TO_BINDING[androidKeyCode]
}
