package com.winlator.renderer;

import androidx.annotation.Keep;

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
    private long preparedRendererGeneration = -1;
    private static boolean supported = false;

    static {
        System.loadLibrary("ahbimage");
    }

    public AHBImage(short width, short height) {
        hardwareBufferPtr = createHardwareBuffer(width, height);
        if (hardwareBufferPtr == 0) {
            System.err.println("Error: Failed to create hardware buffer");
            return;
        }

        virtualData = lockHardwareBuffer(hardwareBufferPtr);
        this.width = nativeGetWidth(hardwareBufferPtr);
        this.height = nativeGetHeight(hardwareBufferPtr);

        if (virtualData == null) {
            System.err.println("Error: Failed to lock hardware buffer");
            destroyHardwareBuffer(hardwareBufferPtr);
            hardwareBufferPtr = 0;
            return;
        }

        boolean swapchainOk = true;
        for (int i = 0; i < swapchainAhbs.length; i++) {
            swapchainAhbs[i] = createHardwareBuffer(width, height);
            if (swapchainAhbs[i] == 0) swapchainOk = false;
        }

        if (!swapchainOk) {
            System.err.println("Error: Failed to create CPU scanout swapchain");
            for (int i = 0; i < swapchainAhbs.length; i++) {
                if (swapchainAhbs[i] != 0) {
                    destroyHardwareBuffer(swapchainAhbs[i]);
                    swapchainAhbs[i] = 0;
                }
            }
        }
    }

    public AHBImage(int socketFd) {
        hardwareBufferPtr = hardwareBufferFromSocket(socketFd);
        if (hardwareBufferPtr != 0) {
            width = nativeGetWidth(hardwareBufferPtr);
            height = nativeGetHeight(hardwareBufferPtr);
        } else {
            System.err.println("Error: Failed to create hardware buffer");
        }
    }

    public short getStride() {
        return stride;
    }

    private boolean hasCompleteScanoutSwapchain() {
        for (long ahb : swapchainAhbs) {
            if (ahb == 0) return false;
        }
        return true;
    }

    void prepareScanoutSources() {
        if (!hasCompleteScanoutSwapchain()) return;

        final long generation = ASurfaceRenderer.getNativeContextGeneration();
        if (generation <= 0) return;
        if (preparedRendererGeneration == generation) return;

        try {
            if (ASurfaceRenderer.nativePrepareCpuSourceBuffers(swapchainAhbs[0], swapchainAhbs[1], swapchainAhbs[2])) {
                preparedRendererGeneration = generation;
            }
        } catch (UnsatisfiedLinkError ignored) {
            // The ASurfaceRenderer native library may not be active.
        }
    }

    private void releaseScanoutSources() {
        if (!hasCompleteScanoutSwapchain()) return;
        try {
            ASurfaceRenderer.nativeReleaseCpuSourceBuffers(
                    swapchainAhbs[0], swapchainAhbs[1], swapchainAhbs[2]);
        } catch (UnsatisfiedLinkError ignored) {
            // The ASurfaceRenderer library/context may already be gone.
        } finally {
            preparedRendererGeneration = -1;
        }
    }

    public long getScanoutHardwareBufferPtr() {
        if (!hasCompleteScanoutSwapchain() || virtualData == null) return 0;

        long targetAhb = swapchainAhbs[swapchainIndex];

        int waitFence;
        synchronized (fenceLock) {
            waitFence = swapchainFences[swapchainIndex];
            swapchainFences[swapchainIndex] = -1;
        }

        if (lastAcquireFence >= 0) {
            nativeCloseFd(lastAcquireFence);
        }

        lastAcquireFence = copyHardwareBuffer(virtualData, targetAhb, (short)width, (short)height, stride, waitFence);

        lastUsedSlot = swapchainIndex;
        swapchainIndex = (swapchainIndex + 1) % swapchainAhbs.length;
        return targetAhb;
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
        if (slot < 0 || slot >= swapchainFences.length) {
            if (fence >= 0) nativeCloseFd(fence);
            return;
        }

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
        releaseScanoutSources();
        if (lastAcquireFence >= 0) {
            nativeCloseFd(lastAcquireFence);
            lastAcquireFence = -1;
        }
        synchronized (fenceLock) {
            for (int i = 0; i < swapchainFences.length; i++) {
                if (swapchainFences[i] >= 0) {
                    nativeCloseFd(swapchainFences[i]);
                    swapchainFences[i] = -1;
                }
            }
        }
        for (int i = 0; i < swapchainAhbs.length; i++) {
            if (swapchainAhbs[i] != 0) {
                destroyHardwareBuffer(swapchainAhbs[i]);
                swapchainAhbs[i] = 0;
            }
        }
        if (hardwareBufferPtr != 0) {
            destroyHardwareBuffer(hardwareBufferPtr);
            hardwareBufferPtr = 0;
        }
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
