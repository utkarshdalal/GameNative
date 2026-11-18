package com.winlator.renderer.lsfg;

import android.content.Context;
import android.util.Log;

import com.winlator.container.Container;
import com.winlator.core.KeyValueSet;

import java.io.File;

public final class LosslessScaling {
    private static final String TAG = "LosslessScaling";
    private static final int STATUS_OK = 0;
    private static final String STORE_DIR = "lsfg";
    private static final String CACHE_FP16 = "lossless_fp16.lsfgcache";
    private static final String CACHE_FP32 = "lossless_fp32.lsfgcache";

    static {
        try {
            System.loadLibrary("vulkan_renderer");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to load libvulkan_renderer: " + t.getMessage());
        }
    }

    private LosslessScaling() {}

    /** Graphics driver the container runs with, or null when it uses the system driver. */
    public static String getDriverName(Container container) {
        if (container == null) return null;
        try {
            KeyValueSet config = new KeyValueSet(container.getGraphicsDriverConfig());
            String version = config.get("version");
            if (version != null && !version.isEmpty() && !version.equalsIgnoreCase("System")) {
                return version;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Resolve the compiled shader cache for {@code dll}, building it when missing or
     * stale. Returns null when no usable cache could be produced.
     */
    public static File resolveOrBuildCache(Context context, File dll, String driverName) {
        if (context == null || dll == null || !dll.isFile()) return null;

        boolean fp16;
        try {
            fp16 = nativeSupportsFp16(driverName, context);
        } catch (Throwable t) {
            fp16 = false;
        }

        File store = new File(context.getFilesDir(), STORE_DIR);
        if (!store.isDirectory() && !store.mkdirs()) return null;
        File cache = new File(store, fp16 ? CACHE_FP16 : CACHE_FP32);

        try {
            if (cache.isFile() && nativeCacheMatchesSource(cache.getAbsolutePath(), dll.getAbsolutePath())) {
                return cache;
            }
            int status = nativeBuildCache(dll.getAbsolutePath(), cache.getAbsolutePath(), fp16);
            if (status != STATUS_OK || !cache.isFile()) {
                if (cache.isFile()) cache.delete();
                Log.w(TAG, "Shader cache build failed with status " + status);
                return null;
            }
            Log.i(TAG, "Built shader cache " + cache.getAbsolutePath() + " (fp16=" + fp16 + ")");
            return cache;
        } catch (Throwable t) {
            if (cache.isFile()) cache.delete();
            Log.w(TAG, "Shader cache build failed: " + t.getMessage());
            return null;
        }
    }

    private static native int nativeBuildCache(String dllPath, String cachePath, boolean preferFp16);

    private static native boolean nativeCacheMatchesSource(String cachePath, String dllPath);

    private static native boolean nativeSupportsFp16(String driverName, Context context);
}
