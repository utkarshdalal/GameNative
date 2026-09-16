package app.gamenative.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SteamAssetDownloadTest {
    @Test
    fun `client package download works before Steam service startup`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val filename = "steamhost-test.tzst"
        val output = File(context.filesDir, filename)
        val previousService = SteamService.instance
        SteamService.instance = null
        mockkObject(SteamService.Companion)
        try {
            coEvery { SteamService.fetchFileWithFallback(filename, any(), context, any()) } coAnswers {
                secondArg<File>().writeText("client-package")
                arg<(Float) -> Unit>(3).invoke(1f)
            }
            var progress = 0f
            SteamService.downloadFile({ progress = it }, this, context, filename).await()
            assertEquals("client-package", output.readText())
            assertEquals(1f, progress)
        } finally {
            unmockkObject(SteamService.Companion)
            SteamService.instance = previousService
            output.delete()
        }
    }
}
