package app.gamenative.ui.component.dialog

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A stack of the currently-open Steam Controller settings dialog windows. Each Compose `Dialog`/`AlertDialog` is a
 * **separate window** with its own view tree, so the controller-nav bridge (which synthesizes DPAD/SELECT key events,
 * see XServerScreen `scUiBridge.nav`) can't reach them by dispatching to the main Compose view — the events have to go
 * to the top dialog's window view. Each SC settings dialog registers its window view + its dismiss callback here via
 * [ScNavDialogCapture] while it's composed; the bridge dispatches DPAD/SELECT to [topView] and routes BACK through
 * [back] (calling the top dialog's own dismiss — a synthetic KEYCODE_BACK does NOT reach a Compose dialog's
 * onDismissRequest, which is why "B" didn't close anything before). This is the universal d-pad nav path; SC pad-mouse
 * is layered on top of it.
 */
object ScNavDialogStack {
    private class Entry(val view: View, val onBack: () -> Unit)

    private val stack = CopyOnWriteArrayList<Entry>()

    /** The top (most-recently-opened) dialog window view, or null when no SC dialog is open. */
    fun topView(): View? = stack.lastOrNull()?.view

    /** True while any SC settings dialog is open. */
    fun isActive(): Boolean = stack.isNotEmpty()

    /** Close the top dialog via its own dismiss callback. Returns true if a dialog was open (and dismissed). */
    fun back(): Boolean {
        val top = stack.lastOrNull() ?: return false
        top.onBack()
        return true
    }

    fun push(view: View, onBack: () -> Unit) {
        stack.removeAll { it.view === view }
        stack.add(Entry(view, onBack))
    }

    fun remove(view: View) {
        stack.removeAll { it.view === view }
    }
}

/**
 * Register the enclosing dialog's window view + dismiss callback in [ScNavDialogStack] for as long as it's composed,
 * so controller-nav key events reach it and "B" closes it. Call once at the top of each SC settings dialog's content
 * (the `LocalView` inside a Compose `Dialog`/`AlertDialog` is that dialog window's own view). [onBack] is the dialog's
 * own dismiss (e.g. its `onDismissRequest`) — invoked when the controller's Back is pressed while this dialog is top.
 */
@Composable
fun ScNavDialogCapture(onBack: () -> Unit) {
    val view = LocalView.current
    val latestOnBack = rememberUpdatedState(onBack)
    DisposableEffect(view) {
        ScNavDialogStack.push(view) { latestOnBack.value() }
        onDispose { ScNavDialogStack.remove(view) }
    }
}
