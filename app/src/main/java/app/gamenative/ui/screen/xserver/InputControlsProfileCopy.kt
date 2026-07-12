package app.gamenative.ui.screen.xserver

import com.winlator.inputcontrols.ControlElement
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.widget.InputControlsView

internal fun copyInputControlsProfileElements(
    sourceProfile: ControlsProfile,
    targetProfile: ControlsProfile,
    view: InputControlsView
) {
    sourceProfile.loadElements(view)
    targetProfile.elements.toList().forEach(targetProfile::removeElement)
    sourceProfile.elements.forEach { targetProfile.addElement(it.copyForView(view)) }
    view.invalidate()
}

private fun ControlElement.copyForView(view: InputControlsView) = ControlElement(view).also { newElement ->
    newElement.setType(type)
    newElement.setShape(shape)
    newElement.setX(x.toInt())
    newElement.setY(y.toInt())
    newElement.setScale(scale)
    newElement.setText(text)
    newElement.setIconId(iconId.toInt())
    newElement.setToggleSwitch(isToggleSwitch)
    newElement.copyButtonAppearanceFrom(this)

    if (type == ControlElement.Type.RANGE_BUTTON) {
        newElement.setRange(range)
        newElement.setOrientation(orientation)
        newElement.setBindingCount(bindingCount)
        newElement.isScrollLocked = isScrollLocked
    }

    for (i in 0 until bindingCount) {
        newElement.setBindingAt(i, getBindingAt(i))
    }

    if (type == ControlElement.Type.SHOOTER_MODE) {
        newElement.shooterMovementType = shooterMovementType
        newElement.shooterLookType = shooterLookType
        newElement.shooterLookSensitivity = shooterLookSensitivity
        newElement.shooterJoystickSize = shooterJoystickSize
    }
}
