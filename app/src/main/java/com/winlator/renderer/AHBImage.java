package com.winlator.renderer;

import androidx.annotation.Keep;

import com.winlator.xserver.Drawable;

import java.nio.ByteBuffer;

public class AHBImage extends NativeTexture {
    private long hardwareBufferPtr;
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
            width = nativeGetWidth(hardwareBufferPtr);
            height = nativeGetHeight(hardwareBufferPtr);
        } else {
            System.err.println("Error: Failed to create hardware buffer");
        }
    }

    public boolean needsRBSwap() {
        return needsRBSwap;
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

    public long getHardwareBufferPtr() {
        return this.hardwareBufferPtr;
    }

    private native long hardwareBufferFromSocket(int fd);

    private native long createHardwareBuffer(short width, short height);

    private native void destroyHardwareBuffer(long hardwareBufferPtr);

    private native ByteBuffer lockHardwareBuffer(long hardwareBufferPtr);

    private native int unlockHardwareBuffer(long hardwareBufferPtr);

    private native int copyHardwareBuffer(ByteBuffer srcBuffer, long dstPtr, short width, short height, short srcStride, int waitFence);

    public static native short nativeGetWidth(long ptr);

    public static native short nativeGetHeight(long ptr);

    private static native void nativeCloseFd(int fd);
}
