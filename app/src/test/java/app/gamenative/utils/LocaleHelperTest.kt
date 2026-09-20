package app.gamenative.utils

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class LocaleHelperTest {
    private val originalLocale = Locale.getDefault()

    @After
    fun restoreDefaultLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun systemDefaultDoesNotWrapContextOrChangeDefaultLocale() {
        val context = mock<Context>()
        assertSame(context, LocaleHelper.applyLanguage(context, ""))
        assertEquals(originalLocale, Locale.getDefault())
        verifyNoInteractions(context)
    }

    @Test
    fun explicitLanguageOnlyOverridesLocaleAndLayoutDirection() {
        val override = captureOverride("en")
        val expected = Configuration().apply { setLocale(Locale.ENGLISH) }
        assertEquals(expected, override)
        assertEquals(Locale.ENGLISH, Locale.getDefault())
    }

    @Test
    fun regionalLanguageIsPreserved() {
        val override = captureOverride("pt-BR")
        assertEquals("pt-BR", override.locales[0].toLanguageTag())
    }

    @Test
    fun languageOverrideDoesNotPinPortraitDimensionsAfterRotation() {
        val override = captureOverride("en")
        val landscape = Configuration().apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
            screenWidthDp = 921
            screenHeightDp = 366
            smallestScreenWidthDp = 411
        }
        val merged = Configuration(landscape).apply { updateFrom(override) }
        assertEquals(landscape.orientation, merged.orientation)
        assertEquals(landscape.screenWidthDp, merged.screenWidthDp)
        assertEquals(landscape.screenHeightDp, merged.screenHeightDp)
        assertEquals(landscape.smallestScreenWidthDp, merged.smallestScreenWidthDp)
        assertEquals("en", merged.locales[0].language)
    }

    @Test
    fun fontScaleDensityNightModeAndInputChangesRemainInherited() {
        val override = captureOverride("en")
        val changed = Configuration().apply {
            fontScale = 1.4f
            densityDpi = 480
            uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL
            keyboard = Configuration.KEYBOARD_QWERTY
            keyboardHidden = Configuration.KEYBOARDHIDDEN_NO
            navigation = Configuration.NAVIGATION_DPAD
        }
        val merged = Configuration(changed).apply { updateFrom(override) }
        assertEquals(changed.fontScale, merged.fontScale, 0f)
        assertEquals(changed.densityDpi, merged.densityDpi)
        assertEquals(changed.uiMode, merged.uiMode)
        assertEquals(changed.keyboard, merged.keyboard)
        assertEquals(changed.keyboardHidden, merged.keyboardHidden)
        assertEquals(changed.navigation, merged.navigation)
    }

    private fun captureOverride(language: String): Configuration {
        val base = Configuration().apply {
            setToDefaults()
            orientation = Configuration.ORIENTATION_PORTRAIT
            screenWidthDp = 411
            screenHeightDp = 902
            smallestScreenWidthDp = 411
            densityDpi = 420
        }
        val resources = mock<Resources>()
        whenever(resources.configuration).thenReturn(base)
        val context = mock<Context>()
        whenever(context.resources).thenReturn(resources)
        val localized = mock<Context>()
        var captured: Configuration? = null
        doAnswer { invocation ->
            captured = Configuration(invocation.getArgument<Configuration>(0))
            localized
        }.whenever(context).createConfigurationContext(any())
        assertSame(localized, LocaleHelper.applyLanguage(context, language))
        return requireNotNull(captured)
    }
}
