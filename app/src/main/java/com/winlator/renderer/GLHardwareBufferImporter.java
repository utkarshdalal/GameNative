package com.winlator.renderer;

import android.hardware.HardwareBuffer;

/**
 * Imports an {@link HardwareBuffer} as a GL texture usable in the *calling thread's* current EGL
 * context — the public Android SDK has no clean Java binding for the
 * eglGetNativeClientBufferANDROID / eglCreateImageKHR / glEGLImageTargetTexture2DOES sequence
 * this needs (GLES11Ext's binding still takes a raw java.nio.Buffer, a legacy pre-HardwareBuffer
 * calling convention), so this is a thin JNI call into the same native library the Quest
 * immersive session already uses (libxrimmersive.so) rather than anything renderer-specific.
 *
 * Must be called from a thread with a current EGL context (e.g. GLRenderer's own GL thread,
 * from inside onDrawFrame/queueEvent) — the resulting texture is only valid in that context.
 */
public final class GLHardwareBufferImporter {
    static {
        System.loadLibrary("xrimmersive");
    }

    private GLHardwareBufferImporter() {}

    /** Returns a GL texture name bound (GL_TEXTURE_2D) to the given buffer's contents in the
     * calling thread's current context, or 0 on failure. */
    public static native int importAsTexture(HardwareBuffer buffer);
}
