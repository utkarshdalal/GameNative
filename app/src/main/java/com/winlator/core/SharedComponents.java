package com.winlator.core;

import android.content.Context;
import android.util.Log;

import com.winlator.xenvironment.ImageFs;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public abstract class SharedComponents {
    private static final String TAG = "SharedComponents";
    private static final String COMPLETE_MARKER = ".complete";
    private static final Map<String, Object> LOCKS = new HashMap<>();

    private static Object lockFor(String componentId) {
        synchronized (LOCKS) {
            Object lock = LOCKS.get(componentId);
            if (lock == null) {
                lock = new Object();
                LOCKS.put(componentId, lock);
            }
            return lock;
        }
    }

    public static File getSharedComponentDir(Context context, String componentId) {
        return new File(new File(ImageFs.getImageFsSharedDir(context), "components"), componentId);
    }

    public static boolean extractAndLink(Context context, String componentId, TarCompressorUtils.Type type, String assetFile, File destDir, OnExtractFileListener onExtractFileListener) {
        return extractAndLink(context, componentId, type, assetFile, null, destDir, onExtractFileListener);
    }

    public static boolean extractAndLink(Context context, String componentId, TarCompressorUtils.Type type, File sourceFile, File destDir, OnExtractFileListener onExtractFileListener) {
        if (sourceFile == null || !sourceFile.isFile()) return false;
        return extractAndLink(context, componentId, type, null, sourceFile, destDir, onExtractFileListener);
    }

    private static boolean extractAndLink(Context context, String componentId, TarCompressorUtils.Type type, String assetFile, File sourceFile, File destDir, OnExtractFileListener onExtractFileListener) {
        if (context == null || componentId == null || destDir == null) return false;

        File sharedDir = getSharedComponentDir(context, componentId);
        String sourceIdentity = assetFile != null ? assetIdentity(context, assetFile) : fileIdentity(sourceFile);
        synchronized (lockFor(componentId)) {
            File marker = new File(sharedDir, COMPLETE_MARKER);
            if (!marker.isFile() || !sourceIdentity.equals(FileUtils.readString(marker))) {
                FileUtils.delete(sharedDir);
                if (!sharedDir.isDirectory() && !sharedDir.mkdirs()) {
                    Log.e(TAG, "Failed to create shared dir for " + componentId);
                    return false;
                }

                boolean extracted = assetFile != null
                        ? TarCompressorUtils.extract(type, context, assetFile, sharedDir)
                        : TarCompressorUtils.extract(type, sourceFile, sharedDir);

                if (!extracted) {
                    Log.e(TAG, "Failed to extract shared component " + componentId);
                    FileUtils.delete(sharedDir);
                    return false;
                }

                if (!FileUtils.write(marker, sourceIdentity.getBytes(StandardCharsets.UTF_8))) {
                    Log.e(TAG, "Failed to write completion marker for " + componentId);
                    FileUtils.delete(sharedDir);
                    return false;
                }
            }
        }

        if (!destDir.isDirectory() && !destDir.mkdirs()) return false;
        return linkTree(sharedDir, sharedDir, destDir, onExtractFileListener);
    }

    private static String assetIdentity(Context context, String assetFile) {
        long installed = 0;
        try {
            installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        }
        catch (Exception e) {
            Log.w(TAG, "Failed to read package info: " + e);
        }
        return "asset:" + assetFile + ":" + installed;
    }

    private static String fileIdentity(File file) {
        return "file:" + file.getName() + ":" + file.length() + ":" + file.lastModified();
    }

    private static boolean linkTree(File sharedDir, File currentDir, File destDir, OnExtractFileListener onExtractFileListener) {
        File[] files = currentDir.listFiles();
        if (files == null) return true;

        int sharedPathLength = sharedDir.getAbsolutePath().length() + 1;
        for (File file : files) {
            String relativePath = file.getAbsolutePath().substring(sharedPathLength);
            String fileName = file.getName();
            if (relativePath.equals(COMPLETE_MARKER)) continue;
            if (fileName.startsWith("._") || relativePath.contains("__MACOSX/")) continue;

            boolean isSymlink = FileUtils.isSymlink(file);
            boolean isDirectory = !isSymlink && file.isDirectory();

            File destFile = new File(destDir, relativePath);
            if (onExtractFileListener != null) {
                destFile = onExtractFileListener.onExtractFile(destFile, isDirectory || isSymlink ? 0 : file.length());
            }

            if (destFile != null) {
                if (isDirectory) {
                    if (!destFile.isDirectory() && !destFile.mkdirs()) return false;
                    FileUtils.chmod(destFile, 0771);
                }
                else if (isSymlink) {
                    FileUtils.symlink(FileUtils.readSymlink(file), destFile.getAbsolutePath());
                }
                else if (!FileUtils.link(file, destFile)) return false;
            }

            if (isDirectory && !linkTree(sharedDir, file, destDir, onExtractFileListener)) return false;
        }
        return true;
    }
}
