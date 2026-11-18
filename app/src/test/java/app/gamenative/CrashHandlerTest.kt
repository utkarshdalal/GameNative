package app.gamenative

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CrashHandlerTest {
    private var original: Thread.UncaughtExceptionHandler? = null
    private val context = mockk<Context>(relaxed = true)

    @Before
    fun setUp() {
        original = Thread.getDefaultUncaughtExceptionHandler()
        every { context.applicationContext } returns context
        mockkObject(PrefManager)
        every { PrefManager.recentlyCrashed = any() } returns Unit
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(original)
        unmockkAll()
    }

    @Test
    fun repeatedInitializeKeepsOneCrashHandlerInTheChain() {
        val system = mockk<Thread.UncaughtExceptionHandler>(relaxed = true)
        Thread.setDefaultUncaughtExceptionHandler(system)

        repeat(3) { CrashHandler.initialize(context) }

        val installed = Thread.getDefaultUncaughtExceptionHandler()
        assertTrue(installed is CrashHandler)
        val throwable = RuntimeException("boom")
        installed!!.uncaughtException(Thread.currentThread(), throwable)
        verify(exactly = 1) { system.uncaughtException(any(), throwable) }
    }

    @Test
    fun failedSaveDispatchesToDefaultHandlerOnce() {
        val system = mockk<Thread.UncaughtExceptionHandler>(relaxed = true)
        every { context.getExternalFilesDir(null) } throws IllegalStateException("no storage")
        val handler = CrashHandler(context, system)

        val throwable = RuntimeException("boom")
        handler.uncaughtException(Thread.currentThread(), throwable)

        verify(exactly = 1) { system.uncaughtException(any(), throwable) }
    }
}
