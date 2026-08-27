package app.gamenative.ui.screen.xr

import android.content.Context
import androidx.lifecycle.ViewModel
import app.gamenative.ui.screen.xr.windows.WindowsVrRuntimeService

/** Retains the Windows VR control server across Android activity configuration recreation. */
internal class ImmersiveRuntimeViewModel : ViewModel() {
    private var windowsVrRuntimeService: WindowsVrRuntimeService? = null

    fun windowsVrRuntimeService(context: Context): WindowsVrRuntimeService =
        windowsVrRuntimeService ?: WindowsVrRuntimeService(context.applicationContext).also {
            windowsVrRuntimeService = it
        }

    fun closeWindowsVrRuntimeService() {
        windowsVrRuntimeService?.close()
        windowsVrRuntimeService = null
    }

    override fun onCleared() {
        closeWindowsVrRuntimeService()
    }
}
