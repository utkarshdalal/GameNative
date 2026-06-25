package com.winlator.renderer;

import androidx.annotation.Keep;
import com.winlator.xserver.Drawable;
import java.nio.ByteBuffer;

public class GPUImage extends Texture {
    private long hardwareBufferPtr;
    private long imageKHRPtr;
    private ByteBuffer virtualData;
    private short stride;
    private int width = -1;
    private int height = -1;
    private long[] swapchainAhbs = new long[3];
    private int swapchainIndex = 0;
    private static boolean supported = false;

    static {
        System.loadLibrary("gpuimage");
    }

    public GPUImage(short width, short height) {
        hardwareBufferPtr = createHardwareBuffer(width, height);
        if (hardwareBufferPtr != 0) {
            virtualData = lockHardwareBuffer(hardwareBufferPtr);
            this.width = nativeGetWidth(hardwareBufferPtr);
            this.height = nativeGetHeight(hardwareBufferPtr);
            for (int i = 0; i < 3; i++)
                swapchainAhbs[i] = createHardwareBuffer(width, height);
            if (virtualData == null) {
                System.err.println("Error: Failed to lock hardware buffer");
                destroyHardwareBuffer(hardwareBufferPtr);
                hardwareBufferPtr = 0;
            }
        } else {
            System.err.println("Error: Failed to create hardware buffer");
        }
    }

    public GPUImage(int socketFd) {
        hardwareBufferPtr = hardwareBufferFromSocket(socketFd);
        if (hardwareBufferPtr != 0) {
            virtualData = lockHardwareBuffer(hardwareBufferPtr);
            width = nativeGetWidth(hardwareBufferPtr);
            height = nativeGetHeight(hardwareBufferPtr);
            if (virtualData == null) {
                System.err.println("Error: Failed to lock hardware buffer");
                destroyHardwareBuffer(hardwareBufferPtr);
                hardwareBufferPtr = 0;
            }
        } else {
            System.err.println("Error: Failed to create hardware buffer");
        }
    }

    @Override
    public void allocateTexture(short width, short height, ByteBuffer data) {
        if (isAllocated()) return;
        super.allocateTexture(width, height, null);
        if (hardwareBufferPtr != 0) {
            imageKHRPtr = createImageKHR(hardwareBufferPtr, textureId);
            if (imageKHRPtr == 0) {
                System.err.println("Error: Failed to create EGL image");
                destroyHardwareBuffer(hardwareBufferPtr);
                hardwareBufferPtr = 0;
            }
        }
    }

    @Override
    public void updateFromDrawable(Drawable drawable) {
        if (!isAllocated()) allocateTexture(drawable.width, drawable.height, null);
        needsUpdate = false;
    }

    public short getStride() {
        return stride;
    }

    public long getScanoutHardwareBufferPtr() {
        if (swapchainAhbs[0] != 0 && virtualData != null) {
            long targetAhb = swapchainAhbs[swapchainIndex];
            copyHardwareBuffer(virtualData, targetAhb, (short)width, (short)height, stride);
            swapchainIndex = (swapchainIndex + 1) % 3;
            return targetAhb;
        }
        return hardwareBufferPtr;
    }

    public int getHeight() { return height; }

    public int getWidth() { return width; }

    @Keep
    private void setStride(short stride) {
        this.stride = stride;
    }

    public ByteBuffer getVirtualData() {
        return virtualData;
    }

    @Override
    public void destroy() {
        if (imageKHRPtr != 0) {
            destroyImageKHR(imageKHRPtr);
            imageKHRPtr = 0;
        }
        for (int i = 0; i < 3; i++) {
            if (swapchainAhbs[i] != 0) {
                destroyHardwareBuffer(swapchainAhbs[i]);
                swapchainAhbs[i] = 0;
            }
        }
        if (hardwareBufferPtr != 0) {
            destroyHardwareBuffer(hardwareBufferPtr);
            hardwareBufferPtr = 0;
        }
        virtualData = null;
        super.destroy();
    }

    public static boolean isSupported() {
        return supported;
    }

    public static void checkIsSupported() {
        final short size = 8;
        GPUImage gpuImage = new GPUImage(size, size);
        gpuImage.allocateTexture(size, size, null);
        supported = gpuImage.hardwareBufferPtr != 0 && gpuImage.imageKHRPtr != 0 && gpuImage.virtualData != null;
        gpuImage.destroy();
    }

    public long getHardwareBufferPtr() {
        return this.hardwareBufferPtr;
    }

    public void lock() {
        if (hardwareBufferPtr != 0 && virtualData == null) {
            virtualData = lockHardwareBuffer(hardwareBufferPtr);
        }
    }

    public int unlock() {
        if (hardwareBufferPtr != 0 && virtualData != null) {
            int fenceFd = unlockHardwareBuffer(hardwareBufferPtr);
            virtualData = null;
            return fenceFd;
        }
        return -1;
    }

    private native long hardwareBufferFromSocket(int fd);

    private native long createHardwareBuffer(short width, short height);

    private native void destroyHardwareBuffer(long hardwareBufferPtr);

    private native ByteBuffer lockHardwareBuffer(long hardwareBufferPtr);

    private native int unlockHardwareBuffer(long hardwareBufferPtr);

    private native long createImageKHR(long hardwareBufferPtr, int textureId);

    private native void destroyImageKHR(long imageKHRPtr);

    private native void copyHardwareBuffer(ByteBuffer srcBuffer, long dstPtr, short width, short height, short srcStride);

    public static native short nativeGetWidth(long ptr);

    public static native short nativeGetHeight(long ptr);
}
