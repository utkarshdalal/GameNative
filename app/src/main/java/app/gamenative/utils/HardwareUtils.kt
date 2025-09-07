package app.gamenative.utils

import android.content.Context
import android.os.Build
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceHolder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object HardwareUtils {
    
    /**
     * Gets a human-readable device model name
     */
    fun getMachineName(): String {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        return if (model.startsWith(manufacturer)) {
            model
        } else {
            "$manufacturer $model"
        }
    }
    
    /**
     * Gets GPU information if available
     */
    suspend fun getGPUInfo(context: Context): String? = suspendCancellableCoroutine { continuation ->
        try {
            val surfaceView = GLSurfaceView(context)
            surfaceView.setEGLContextClientVersion(2)
            surfaceView.setRenderer(object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
                    try {
                        val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
                        val vendor = GLES20.glGetString(GLES20.GL_VENDOR)
                        if (renderer != null) {
                            val gpuInfo = if (vendor != null && !renderer.contains(vendor)) {
                                "$vendor $renderer"
                            } else {
                                renderer
                            }
                            continuation.resume(gpuInfo)
                        } else {
                            continuation.resume(null)
                        }
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {}
                override fun onDrawFrame(gl: GL10) {}
            })
            
            // Create a temporary surface
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {}
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            })
            
            // Timeout after 2 seconds
            continuation.invokeOnCancellation {
                surfaceView.onPause()
            }
        } catch (e: Exception) {
            if (continuation.isActive) {
                continuation.resume(null)
            }
        }
    }
}