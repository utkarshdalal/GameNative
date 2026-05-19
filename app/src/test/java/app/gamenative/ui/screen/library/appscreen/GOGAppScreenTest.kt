package app.gamenative.ui.screen.library.appscreen

import android.content.Context
import app.gamenative.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class GOGAppScreenTest {
    @Test
    fun `forceCloudSync shows success messages when sync succeeds`() = runBlocking {
        val context = mockContext()
        val messages = mutableListOf<String>()
        var calledAppId: String? = null
        var calledPreferredAction: String? = null

        GOGAppScreen.forceCloudSync(
            context = context,
            appId = "app-123",
            syncCloudSaves = { _, appId, preferredAction ->
                calledAppId = appId
                calledPreferredAction = preferredAction
                true
            },
            showSnackbar = { messages += it },
        )

        assertEquals("app-123", calledAppId)
        assertEquals("auto", calledPreferredAction)
        assertEquals(listOf("starting", "success"), messages)
    }

    @Test
    fun `forceCloudSync shows failure message when sync fails`() = runBlocking {
        val context = mockContext()
        val messages = mutableListOf<String>()

        GOGAppScreen.forceCloudSync(
            context = context,
            appId = "app-123",
            syncCloudSaves = { _, _, _ -> false },
            showSnackbar = { messages += it },
        )

        assertEquals(listOf("starting", "failed"), messages)
    }

    @Test
    fun `forceCloudSync logs and shows error when sync throws`() = runBlocking {
        val context = mockContext()
        val messages = mutableListOf<String>()
        var loggedError: Throwable? = null

        GOGAppScreen.forceCloudSync(
            context = context,
            appId = "app-123",
            syncCloudSaves = { _, _, _ -> throw IllegalStateException("boom") },
            showSnackbar = { messages += it },
            logError = { loggedError = it },
        )

        assertTrue(loggedError is IllegalStateException)
        assertEquals("boom", loggedError?.message)
        assertEquals(listOf("starting", "error: boom"), messages)
    }

    private fun mockContext(): Context {
        val context = mock<Context>()
        whenever(context.getString(R.string.library_cloud_sync_starting)).thenReturn("starting")
        whenever(context.getString(R.string.library_cloud_sync_success)).thenReturn("success")
        whenever(context.getString(R.string.library_cloud_sync_failed)).thenReturn("failed")
        whenever(context.getString(eq(R.string.library_cloud_sync_error), any())).thenAnswer {
            "error: ${it.arguments[1]}"
        }
        return context
    }
}
