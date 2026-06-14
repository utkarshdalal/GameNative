package com.winlator.renderer;

import androidx.annotation.Keep;
import com.winlator.xserver.Drawable;
import java.nio.ByteBuffer;

public class GPUImage extends Texture {
    private long ahbPtr = 0;
    private ByteBuffer mapping = null;
    private int pendingFence = -1;
    private short stride = 0;
    private int width = -1;
    private int height = -1;

    private long imageKHRPtr = 0;
    private static boolean supported = false;

    static { System.loadLibrary("gpuimage"); }

    public GPUImage(short width, short height) {
        ahbPtr = nativeCreateHardwareBuffer(width, height);
        if (ahbPtr != 0) {
            stride = nativeGetStride(ahbPtr);
            this.width = nativeGetWidth(ahbPtr);
            this.height = nativeGetHeight(ahbPtr);
            mapping = nativeLockHardwareBuffer(ahbPtr, -1, null);
        }
    }

    public GPUImage(int socketFd) {
        ahbPtr = nativeHardwareBufferFromSocket(socketFd);
        if (ahbPtr != 0) {
            stride = nativeGetStride(ahbPtr);
            width = nativeGetWidth(ahbPtr);
            height = nativeGetHeight(ahbPtr);
            mapping = null;
        }
    }

    @Override
    public void allocateTexture(short width, short height, ByteBuffer data) {
        if (isAllocated()) return;

        super.allocateTexture(width, height, null);
        if (ahbPtr != 0) {
            imageKHRPtr = nativeCreateImageKHR(ahbPtr, textureId);
            if (imageKHRPtr == 0) {
                android.util.Log.e("GPUImage", "Failed to create EGL image");
            }
        }
    }

    @Override
    public void updateFromDrawable(Drawable drawable) {
        if (!isAllocated()) allocateTexture(drawable.width, drawable.height, null);
        needsUpdate = false;
    }

    public long getHardwareBufferPtr() { return ahbPtr; }
    public ByteBuffer getVirtualData() {
        if (mapping == null && ahbPtr != 0) {
            mapping = nativeLockHardwareBuffer(ahbPtr, -1, null);
        }
        return mapping;
    }

    public int getHeight() { return height; }

    public int getWidth() { return width; }
    public short getStride() { return stride; }

    public int unlock() {
        if (ahbPtr == 0) return -1;
        int readyFence = nativeUnlockHardwareBuffer(ahbPtr);
        pendingFence = readyFence;
        return readyFence;
    }

    public void lock() {
        if (ahbPtr == 0) return;
        int fence = pendingFence;
        pendingFence = -1;
        mapping = nativeLockHardwareBuffer(ahbPtr, fence, mapping);
    }

    @Override
    public void destroy() {
        if (imageKHRPtr != 0) {
            nativeDestroyImageKHR(imageKHRPtr);
            imageKHRPtr = 0;
        }
        if (ahbPtr != 0) {
            if (mapping != null) {
                nativeUnlockHardwareBuffer(ahbPtr);
                mapping = null;
            }
            nativeDestroyHardwareBuffer(ahbPtr);
            ahbPtr = 0;
        }
        if (pendingFence >= 0) {
            nativeCloseFence(pendingFence);
            pendingFence = -1;
        }
        super.destroy();
    }

    public static boolean isSupported() { return supported; }
    public static void checkIsSupported() {
        GPUImage gpuImage = new GPUImage((short)8, (short)8);
        supported = gpuImage.ahbPtr != 0 && gpuImage.mapping != null;
        gpuImage.destroy();
    }

    private native long nativeHardwareBufferFromSocket(int fd);
    private native long nativeCreateHardwareBuffer(short width, short height);
    private native void nativeDestroyHardwareBuffer(long ptr);
    private native int nativeUnlockHardwareBuffer(long ptr);
    private native ByteBuffer nativeLockHardwareBuffer(long ptr, int fenceFd, ByteBuffer oldBuffer);
    private native short nativeGetStride(long ptr);
    private native short nativeGetWidth(long ptr);
    private native short nativeGetHeight(long ptr);
    private native void nativeCloseFence(int fenceFd);

    private native long nativeCreateImageKHR(long ptr, int textureId);
    private native void nativeDestroyImageKHR(long imagePtr);
}
