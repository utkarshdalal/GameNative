package app.gamenative.ui.screen.xr

import android.hardware.HardwareBuffer
import android.opengl.GLES20
import com.winlator.renderer.GLHardwareBufferImporter
import com.winlator.renderer.XrFrameBridge
import timber.log.Timber

/**
 * [XrFrameBridge] for the legacy GL renderer: allocates a [HardwareBuffer], imports it as a
 * GL texture/FBO inside GLRenderer's own EGL context (via [GLHardwareBufferImporter], since the
 * public SDK has no binding for that import), and hands the same buffer to the native OpenXR
 * session to sample directly — no PixelCopy, no CPU-side bitmap.
 */
class DirectGLBridge(
    private val onBufferReady: (HardwareBuffer) -> Unit,
) : XrFrameBridge {

    private var width = 0
    private var height = 0
    private var hardwareBuffer: HardwareBuffer? = null
    private var texture = 0
    private var framebuffer = 0
    private var initFailed = false
    private var announcedReady = false

    /** Must be called from GLRenderer's own GL thread (e.g. via GLSurfaceView.queueEvent) —
     * everything here (buffer import, texture/FBO creation) needs the calling EGL context to be
     * current. Safe to call again with a new size; the old buffer/resources are released first. */
    fun ensureAllocated(targetWidth: Int, targetHeight: Int) {
        if (initFailed) return
        if (hardwareBuffer != null && targetWidth == width && targetHeight == height) return
        if (targetWidth <= 0 || targetHeight <= 0) return

        release()
        width = targetWidth
        height = targetHeight

        val buffer = try {
            HardwareBuffer.create(
                width,
                height,
                HardwareBuffer.RGBA_8888,
                1,
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
            )
        } catch (t: Throwable) {
            Timber.e(t, "Immersive: DirectGLBridge failed to allocate HardwareBuffer %dx%d — falling back to PixelCopy", width, height)
            initFailed = true
            return
        }

        val importedTexture = GLHardwareBufferImporter.importAsTexture(buffer)
        if (importedTexture == 0) {
            Timber.e("Immersive: DirectGLBridge native import failed — falling back to PixelCopy")
            buffer.close()
            initFailed = true
            return
        }

        val framebuffers = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffers, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, importedTexture, 0)
        val fboStatus = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (fboStatus != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Timber.e("Immersive: DirectGLBridge FBO incomplete (0x%x) — falling back to PixelCopy", fboStatus)
            GLES20.glDeleteTextures(1, intArrayOf(importedTexture), 0)
            GLES20.glDeleteFramebuffers(1, framebuffers, 0)
            buffer.close()
            initFailed = true
            return
        }

        hardwareBuffer = buffer
        texture = importedTexture
        framebuffer = framebuffers[0]
        Timber.i("Immersive: DirectGLBridge allocated shared buffer %dx%d, fbo=%d texture=%d", width, height, framebuffer, texture)

        if (!announcedReady) {
            announcedReady = true
            onBufferReady(buffer)
        }
    }

    override fun beginFrame(): IntArray? {
        val fbo = framebuffer
        if (fbo == 0) return null
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        return intArrayOf(width, height)
    }

    override fun endFrame() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    fun release() {
        if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        hardwareBuffer?.close()
        texture = 0
        framebuffer = 0
        hardwareBuffer = null
        announcedReady = false
    }
}
