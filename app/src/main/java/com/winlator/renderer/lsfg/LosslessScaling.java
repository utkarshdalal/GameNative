package com.winlator.renderer.lsfg;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.winlator.container.Container;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

public final class LosslessScaling {
    public static final int STATUS_OK = 0;
    public static final int STATUS_NOT_INSTALLED = 1;
    public static final int STATUS_UNREADABLE_FILE = 2;
    public static final int STATUS_NOT_PORTABLE_EXECUTABLE = 3;
    public static final int STATUS_MISSING_SHADERS = 4;
    public static final int STATUS_TRANSLATION_FAILED = 5;
    public static final int STATUS_CACHE_UNUSABLE = 6;

    public static final int VARIANT_NONE = 0;
    public static final int VARIANT_FP16 = 1;
    public static final int VARIANT_FP32 = 2;
    public static final int VARIANT_DXBC = 3;

    private static final String TAG = "LosslessScaling";
    public static final String DLL_NAME = "Lossless.dll";
    private static final String STORE_DIR = "lsfg";
    private static final String CACHE_FP32 = "shaders-fp32.cache";
    private static final String CACHE_FP16 = "shaders-fp16.cache";
    private static final String STAGED_DLL = "Lossless.staged";

    private static final String[] DRIVE_C_CANDIDATES = {
        "Program Files (x86)/Steam/steamapps/common/Lossless Scaling/" + DLL_NAME,
        "Program Files/Steam/steamapps/common/Lossless Scaling/" + DLL_NAME,
        "Program Files (x86)/Lossless Scaling/" + DLL_NAME,
        "Program Files/Lossless Scaling/" + DLL_NAME,
    };

    private static final String[] DRIVE_ROOT_CANDIDATES = {
        "SteamLibrary/steamapps/common/Lossless Scaling/" + DLL_NAME,
        "steamapps/common/Lossless Scaling/" + DLL_NAME,
        "Steam/steamapps/common/Lossless Scaling/" + DLL_NAME,
        "Lossless Scaling/" + DLL_NAME,
        DLL_NAME,
    };

    private static Boolean gpuSupported = null;
    private static String gpuSupportedDriver = null;

    static {
        try {
            System.loadLibrary("winlator");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to load libwinlator: " + t.getMessage());
        }
    }

    private LosslessScaling() {}

    public static File getStoreDir(Context context) {
        return new File(context.getFilesDir(), STORE_DIR);
    }

    public static File getDllFile(Context context) {
        return new File(getStoreDir(context), DLL_NAME);
    }

    public static File getCacheFile(Context context, boolean fp16) {
        return new File(getStoreDir(context), fp16 ? CACHE_FP16 : CACHE_FP32);
    }

    public static File resolveCacheFile(Context context, boolean preferFp16) {
        File preferred = getCacheFile(context, preferFp16);
        if (preferred.isFile()) return preferred;
        File fallback = getCacheFile(context, !preferFp16);
        return fallback.isFile() ? fallback : null;
    }

    public static File findAnyDll(Context context, Container container) {
        if (context != null) {
            File stored = getDllFile(context);
            if (stored != null && stored.isFile()) return stored;
        }

        if (container != null) {
            String containerDll = app.gamenative.utils.LsfgVkManager.containerDllPath(container);
            if (containerDll != null) {
                File f = new File(containerDll);
                if (f.isFile()) return f;
            }
        }

        try {
            File steamDll = app.gamenative.utils.LsfgVkManager.findSteamDll();
            if (steamDll != null && steamDll.isFile()) return steamDll;
        } catch (Throwable t) {
            Log.w(TAG, "Failed searching Steam for DLL: " + t.getMessage());
        }

        if (container != null) {
            List<File> list = findInContainers(java.util.Collections.singletonList(container));
            if (!list.isEmpty()) return list.get(0);
        }
        return null;
    }

    public static File resolveOrBuildCache(Context context, Container container, boolean preferFp16) {
        if (context == null) return null;
        File cache = resolveCacheFile(context, preferFp16);
        File dll = findAnyDll(context, container);
        if (cache != null && cache.isFile()) {
            if (dll != null && dll.isFile() && isCacheStale(context, dll)) {
                Log.i(TAG, "Lossless Scaling cache is stale, rebuilding from " + dll.getAbsolutePath());
                installFrom(context, dll);
                File rebuilt = resolveCacheFile(context, preferFp16);
                if (rebuilt != null && rebuilt.isFile()) return rebuilt;
            }
            return cache;
        }

        if (dll != null && dll.isFile()) {
            Log.i(TAG, "Building Lossless Scaling shader cache from " + dll.getAbsolutePath());
            int res = installFrom(context, dll);
            if (res == STATUS_OK) {
                return resolveCacheFile(context, preferFp16);
            }
        }
        return null;
    }

    public static boolean isInstalled(Context context) {
        return resolveCacheFile(context, false) != null || getDllFile(context).isFile() || findAnyDll(context, null) != null;
    }

    public static int getStatus(Context context, boolean preferFp16) {
        File cache = resolveCacheFile(context, preferFp16);
        if (cache == null) {
            File dll = getDllFile(context);
            return (dll != null && dll.isFile()) ? STATUS_OK : STATUS_NOT_INSTALLED;
        }
        try {
            return nativeInspectCache(cache.getAbsolutePath());
        } catch (LinkageError e) {
            return cache.length() > 0 ? STATUS_OK : STATUS_CACHE_UNUSABLE;
        }
    }

    public static boolean isCacheStale(Context context, File dll) {
        if (dll == null || !dll.isFile()) return false;
        File cache = resolveCacheFile(context, true);
        if (cache == null) {
            File stagedDll = getDllFile(context);
            if (!stagedDll.isFile()) return true;
            return stagedDll.length() != dll.length() || stagedDll.lastModified() < dll.lastModified();
        }
        try {
            return !nativeCacheMatchesSource(cache.getAbsolutePath(), dll.getAbsolutePath());
        } catch (LinkageError e) {
            return cache.length() == 0 || cache.lastModified() < dll.lastModified();
        }
    }

    public static int getVariant(Context context, boolean preferFp16) {
        File cache = resolveCacheFile(context, preferFp16);
        if (cache == null) return VARIANT_DXBC;
        try {
            return nativeCacheVariant(cache.getAbsolutePath());
        } catch (LinkageError e) {
            return VARIANT_DXBC;
        }
    }

    public static int dllVariant(File dll) {
        if (dll == null || !dll.isFile()) return VARIANT_NONE;
        try {
            return nativeDllVariant(dll.getAbsolutePath());
        } catch (LinkageError e) {
            return VARIANT_DXBC;
        }
    }

    public static int variantRank(int variant) {
        switch (variant) {
            case VARIANT_FP16: return 3;
            case VARIANT_FP32: return 2;
            case VARIANT_DXBC: return 1;
            default: return 0;
        }
    }

    public static String variantName(int variant) {
        switch (variant) {
            case VARIANT_FP16: return "spirv-fp16";
            case VARIANT_FP32: return "spirv-fp32";
            case VARIANT_DXBC: return "dxbc-translated";
            default: return "none";
        }
    }

    public static int validate(File dll) {
        if (dll == null || !dll.isFile()) return STATUS_NOT_INSTALLED;
        try {
            return nativeValidateDll(dll.getAbsolutePath());
        } catch (LinkageError e) {
            return dll.length() > 500_000 ? STATUS_OK : STATUS_UNREADABLE_FILE;
        }
    }

    public static int installFrom(Context context, File dll) {
        if (dll == null || !dll.isFile()) return STATUS_NOT_INSTALLED;

        File store = getStoreDir(context);
        if (!store.isDirectory() && !store.mkdirs()) return STATUS_CACHE_UNUSABLE;

        // Save a copy of Lossless.dll into store directory so LSFG runtime can deploy it
        File storedDll = getDllFile(context);
        if (!storedDll.getAbsolutePath().equals(dll.getAbsolutePath())) {
            try {
                copyFile(dll, storedDll);
            } catch (IOException e) {
                Log.w(TAG, "Failed to copy DLL to store directory: " + e.getMessage());
            }
        }

        File fp32 = getCacheFile(context, false);
        File fp16 = getCacheFile(context, true);
        deleteQuietly(fp32);
        deleteQuietly(fp16);

        final String source = dll.getAbsolutePath();
        try {
            int status = nativeBuildCache(source, fp32.getAbsolutePath(), false);
            if (status != STATUS_OK) {
                deleteQuietly(fp32);
                return STATUS_OK;
            }

            if (nativeCacheVariant(fp32.getAbsolutePath()) == VARIANT_FP16) {
                if (!fp32.renameTo(fp16)) {
                    deleteQuietly(fp32);
                    return STATUS_OK;
                }
                logInstalled(dll, VARIANT_FP16, false);
                return STATUS_OK;
            }

            if (nativeBuildCache(source, fp16.getAbsolutePath(), true) != STATUS_OK
                    || nativeCacheVariant(fp16.getAbsolutePath()) != VARIANT_FP16) {
                deleteQuietly(fp16);
                logInstalled(dll, nativeCacheVariant(fp32.getAbsolutePath()), false);
                return STATUS_OK;
            }

            logInstalled(dll, nativeCacheVariant(fp32.getAbsolutePath()), true);
            return STATUS_OK;
        } catch (LinkageError e) {
            Log.i(TAG, "Native cache builder not available, DLL stored for layer");
            return STATUS_OK;
        }
    }

    public static int installFrom(Context context, Uri uri) {
        if (uri == null) return STATUS_NOT_INSTALLED;

        File staged = new File(context.getCacheDir(), STORE_DIR + File.separator + STAGED_DLL);
        File parent = staged.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return STATUS_CACHE_UNUSABLE;
        }

        try {
            copyToFile(context, uri, staged);
        } catch (IOException e) {
            deleteQuietly(staged);
            Log.w(TAG, "Unable to stage the selected file", e);
            return STATUS_UNREADABLE_FILE;
        }

        try {
            return installFrom(context, staged);
        } finally {
            deleteQuietly(staged);
        }
    }

    public static boolean remove(Context context) {
        boolean removed = deleteQuietly(getCacheFile(context, false));
        removed |= deleteQuietly(getCacheFile(context, true));
        removed |= deleteQuietly(getDllFile(context));
        return removed;
    }

    public static List<File> findInContainers(Collection<Container> containers) {
        LinkedHashSet<File> found = new LinkedHashSet<>();
        if (containers == null) return new ArrayList<>(found);

        for (Container container : containers) {
            if (container == null || container.getRootDir() == null) continue;

            File driveC = new File(container.getRootDir(), ".wine/drive_c");
            for (String candidate : DRIVE_C_CANDIDATES) {
                addIfReadable(found, new File(driveC, candidate));
            }

            for (String[] drive : container.drivesIterator()) {
                if (drive.length < 2 || drive[1] == null || drive[1].isEmpty()) continue;
                File root = new File(drive[1]);
                for (String candidate : DRIVE_ROOT_CANDIDATES) {
                    addIfReadable(found, new File(root, candidate));
                }
            }
        }
        return new ArrayList<>(found);
    }

    public static boolean isSupportedByGpu() {
        return isSupportedByGpu(null, null);
    }

    public static boolean isSupportedByGpu(Context context) {
        return isSupportedByGpu(context, null);
    }

    public static boolean isSupportedByGpu(Context context, String driverName) {
        final String key = driverName == null ? "" : driverName;
        if (gpuSupported != null && key.equals(gpuSupportedDriver)) return gpuSupported;

        try {
            boolean supported = nativeSupportsFrameGeneration(driverName, context);
            gpuSupported = supported;
            gpuSupportedDriver = key;
            return supported;
        } catch (LinkageError e) {
            gpuSupported = true;
            gpuSupportedDriver = key;
            return true;
        }
    }

    public static void invalidateGpuSupport() {
        gpuSupported = null;
        gpuSupportedDriver = null;
    }

    private static void logInstalled(File source, int variant, boolean bothVariants) {
        Log.i(TAG, "Shader cache built from " + source.getAbsolutePath()
                + " (" + source.length() + " bytes): variant=" + variantName(variant)
                + (bothVariants ? " (fp16 alternate available)" : ""));
    }

    private static void addIfReadable(LinkedHashSet<File> target, File candidate) {
        if (candidate.isFile() && candidate.canRead()) target.add(candidate);
    }

    private static void copyToFile(Context context, Uri uri, File destination) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("openInputStream returned null");
            try (OutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
                output.flush();
            }
        }
    }

    private static void copyFile(File source, File destination) throws IOException {
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
            output.flush();
        }
    }

    private static boolean deleteQuietly(File file) {
        return file != null && file.isFile() && file.delete();
    }

    private static native int nativeValidateDll(String dllPath);

    private static native int nativeDllVariant(String dllPath);

    private static native int nativeBuildCache(String dllPath, String cachePath,
                                               boolean preferFp16);

    private static native int nativeInspectCache(String cachePath);

    private static native int nativeCacheVariant(String cachePath);

    private static native boolean nativeCacheMatchesSource(String cachePath, String dllPath);

    private static native boolean nativeSupportsFrameGeneration(String driverName, Context context);
}
