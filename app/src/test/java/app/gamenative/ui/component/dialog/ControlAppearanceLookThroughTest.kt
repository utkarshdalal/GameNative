package app.gamenative.ui.component.dialog

import com.winlator.inputcontrols.ControlElement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ControlAppearanceLookThroughTest {
    @Test
    fun `copying a legacy appearance preserves its shooter fallback`() {
        val source = ControlElement(null).apply {
            lookThroughSetting = null
            setShooterLookThrough(false)
        }
        val target = ControlElement(null).apply {
            lookThroughSetting = true
            setShooterLookThrough(true)
        }

        ControlAppearance.capture(source).applyTo(target)

        assertNull(target.lookThroughSetting)
        assertFalse(target.shooterLookThroughSetting)
        assertFalse(target.isShooterLookThrough)
    }
}
