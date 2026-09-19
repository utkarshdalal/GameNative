package app.gamenative

import android.content.Context
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.mock

class CrashHandlerTest {

    @Test
    fun initializeDoesNotWrapAnExistingCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val context = mock(Context::class.java)
        val existingHandler = CrashHandler(context, previousHandler)

        try {
            Thread.setDefaultUncaughtExceptionHandler(existingHandler)

            CrashHandler.initialize(context)

            assertSame(existingHandler, Thread.getDefaultUncaughtExceptionHandler())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }
}
