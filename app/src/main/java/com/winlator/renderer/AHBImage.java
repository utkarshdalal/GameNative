package com.winlator.renderer;

import androidx.annotation.Keep;

import com.winlator.xserver.Drawable;

import java.nio.ByteBuffer;

public class AHBImage extends NativeTexture {
    private long hardwareBufferPtr;
    private long imageKHRPtr;
    private ByteBuffer virtualData;
    private short stride;
    private int width = -1;
    private int height = -1;
    private final Object fenceLock = new Object();
    private int[] swapchainFences = new int[]{-1, -1, -1};
    private int lastUsedSlot = 0;
    private int lastAcquireFence = -1;
    private long[] swapchainAhbs = new long[3];
    private int swapchainIndex = 0;
    private static boolean supported = false;

    static {
        System.loadLibrary("ahbimage");
    }

    public AHBImage(short width, short height) {
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

    private boolean needsRBSwap = false;

    public AHBImage(int socketFd) {
        hardwareBufferPtr = hardwareBufferFromSocket(socketFd);
        if (hardwareBufferPtr != 0) {
            // Check if bit 0 is set (indicates B8G8R8A8 format needs R/B swap)
            needsRBSwap = (hardwareBufferPtr & 1) != 0;
            hardwareBufferPtr = hardwareBufferPtr & ~1L; // Clear the flag bit
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

    public boolean needsRBSwap() {
        return needsRBSwap;
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

            int waitFence = -1;
            synchronized (fenceLock) {
                waitFence = swapchainFences[swapchainIndex];
                swapchainFences[swapchainIndex] = -1;
            }

            if (lastAcquireFence >= 0) {
                nativeCloseFd(lastAcquireFence);
            }

            lastAcquireFence = copyHardwareBuffer(virtualData, targetAhb, (short)width, (short)height, stride, waitFence);

            lastUsedSlot = swapchainIndex;
            swapchainIndex = (swapchainIndex + 1) % 3;
            return targetAhb;
        }
        return hardwareBufferPtr;
    }

    public int getLastUsedSlot() {
        return lastUsedSlot;
    }

    public int consumeAcquireFence() {
        int fence = lastAcquireFence;
        lastAcquireFence = -1;
        return fence;
    }

    @Keep
    public void setSwapchainFence(int slot, int fence) {
        synchronized (fenceLock) {
            if (swapchainFences[slot] >= 0) {
                nativeCloseFd(swapchainFences[slot]);
            }
            swapchainFences[slot] = fence;
        }
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
        if (lastAcquireFence >= 0) {
            nativeCloseFd(lastAcquireFence);
            lastAcquireFence = -1;
        }
        synchronized (fenceLock) {
            for (int i = 0; i < 3; i++) {
                if (swapchainFences[i] >= 0) {
                    nativeCloseFd(swapchainFences[i]);
                    swapchainFences[i] = -1;
                }
            }
        }
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
        AHBImage gpuImage = new AHBImage(size, size);
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

    private native int copyHardwareBuffer(ByteBuffer srcBuffer, long dstPtr, short width, short height, short srcStride, int waitFence);

    public static native short nativeGetWidth(long ptr);

    public static native short nativeGetHeight(long ptr);

    private static native void nativeCloseFd(int fd);
}
