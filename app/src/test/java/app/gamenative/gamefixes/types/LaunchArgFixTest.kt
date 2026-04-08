package app.gamenative.gamefixes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.winlator.container.Container
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LaunchArgFixTest {
    private lateinit var context: Context
    private lateinit var container: Container

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = mockk(relaxed = true)
    }

    @Test
    fun apply_setsLaunchArgs_whenContainerHasNoCustomArgs() {
        val fix = LaunchArgFix("-lang=eng")
        every { container.execArgs } returns ""
        every { container.execArgs = any() } just runs
        every { container.saveData() } just runs

        val result = fix.apply(context, "1129934535", "/tmp/game", "A:\\", container)

        assertTrue(result)
        verify(exactly = 1) { container.execArgs = "-lang=eng" }
        verify(exactly = 1) { container.saveData() }
    }

    @Test
    fun apply_doesNotOverride_whenContainerAlreadyHasCustomArgs() {
        val fix = LaunchArgFix("-lang=eng")
        every { container.execArgs } returns "-novid"

        val result = fix.apply(context, "1129934535", "/tmp/game", "A:\\", container)

        assertTrue(result)
        verify(exactly = 0) { container.execArgs = any() }
        verify(exactly = 0) { container.saveData() }
    }

    @Test
    fun apply_applies_whenContainerExecArgsIsWhitespace() {
        val fix = LaunchArgFix("-lang=eng")
        every { container.execArgs } returns "   "
        every { container.execArgs = any() } just runs
        every { container.saveData() } just runs

        val result = fix.apply(context, "1129934535", "/tmp/game", "A:\\", container)

        assertTrue(result)
        verify(exactly = 1) { container.execArgs = "-lang=eng" }
        verify(exactly = 1) { container.saveData() }
    }

    @Test
    fun apply_returnsFalse_whenContainerSaveThrows() {
        val fix = LaunchArgFix("-lang=eng")
        every { container.execArgs } returns ""
        every { container.execArgs = any() } just runs
        every { container.saveData() } throws IllegalStateException("boom")

        val result = fix.apply(context, "1129934535", "/tmp/game", "A:\\", container)

        assertFalse(result)
    }
}
