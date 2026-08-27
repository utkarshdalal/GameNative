package app.gamenative

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import app.gamenative.ui.screen.xr.ImmersiveXrActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AndroidXrLaunchTest {

    @Test
    fun androidXrOpenXrFeatureIsRecognizedAsHeadset() {
        val packageManager = mock<PackageManager>()
        val context = mock<Context>()
        whenever(context.packageManager).thenReturn(packageManager)
        whenever(packageManager.hasSystemFeature("android.hardware.vr.headtracking")).thenReturn(false)
        whenever(packageManager.hasSystemFeature("android.software.xr.api.openxr")).thenReturn(true)

        assertTrue(MainActivity.isHeadset(context))
    }

    @Test
    fun activityLaunchStaysOnFlatLauncherTask() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        val intent = ImmersiveXrActivity.createLaunchIntent(activity, "STEAM_123", false)

        assertFalse(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(ImmersiveXrActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun nonActivityLaunchUsesNewTask() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val intent = ImmersiveXrActivity.createLaunchIntent(context, "STEAM_123", true)

        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
