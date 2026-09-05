package app.gamenative.ui.screen.xserver

import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlElement
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.RadialMenu
import com.winlator.widget.InputControlsView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class InputControlsProfileCopyTest {
    @Test
    fun copyReplacesProfileContentsAndNotifiesView() {
        val source = mock<ControlsProfile>()
        val target = mock<ControlsProfile>()
        val view = mock<InputControlsView>()
        val sourceElement = ControlElement(view).apply {
            setType(ControlElement.Type.BUTTON)
            setX(24)
            setY(48)
            text = "Jump"
            setBinding(Binding.KEY_SPACE)
        }
        val oldTargetElement = ControlElement(view).apply {
            setType(ControlElement.Type.BUTTON)
            setBinding(Binding.KEY_E)
        }
        val sourceRadialMenu = RadialMenu.createDefault()
        `when`(source.elements).thenReturn(arrayListOf(sourceElement))
        `when`(target.elements).thenReturn(arrayListOf(oldTargetElement))
        `when`(source.defaultRadialMenu).thenReturn(sourceRadialMenu)

        copyInputControlsProfileElements(source, target, view)

        verify(target).removeElement(oldTargetElement)
        val elementCaptor = argumentCaptor<ControlElement>()
        verify(target).addElement(elementCaptor.capture())
        val copiedElement = elementCaptor.firstValue
        assertNotSame(sourceElement, copiedElement)
        assertEquals(sourceElement.type, copiedElement.type)
        assertEquals(sourceElement.x, copiedElement.x)
        assertEquals(sourceElement.y, copiedElement.y)
        assertEquals(sourceElement.text, copiedElement.text)
        assertEquals(sourceElement.getBindingComboAt(0), copiedElement.getBindingComboAt(0))

        val radialMenuCaptor = argumentCaptor<RadialMenu>()
        verify(target).setDefaultRadialMenu(radialMenuCaptor.capture())
        assertNotSame(sourceRadialMenu, radialMenuCaptor.firstValue)
        assertEquals(sourceRadialMenu.toJSONObject().toString(), radialMenuCaptor.firstValue.toJSONObject().toString())
        verify(view).onControlsProfileContentChanged(true)
        verify(view).invalidate()
    }
}
