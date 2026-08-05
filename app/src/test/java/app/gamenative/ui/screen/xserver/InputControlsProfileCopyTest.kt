package app.gamenative.ui.screen.xserver

import com.winlator.inputcontrols.ControlElement
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.RadialMenu
import com.winlator.widget.InputControlsView
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class InputControlsProfileCopyTest {
    @Test
    fun copyNotifiesViewThatProfileContentWasReplaced() {
        val source = mock<ControlsProfile>()
        val target = mock<ControlsProfile>()
        val view = mock<InputControlsView>()
        `when`(source.elements).thenReturn(arrayListOf<ControlElement>())
        `when`(target.elements).thenReturn(arrayListOf<ControlElement>())
        `when`(source.defaultRadialMenu).thenReturn(RadialMenu.createDefault())
        copyInputControlsProfileElements(source, target, view)

        verify(view).onControlsProfileContentChanged(true)
        verify(view).invalidate()
    }
}
