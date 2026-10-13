package com.winlator.container;

import android.content.Context;
import android.util.Log;

import com.winlator.contents.ContentsManager;
import com.winlator.core.FileUtils;
import com.winlator.core.OnExtractFileListener;
import com.winlator.core.WineInfo;
import com.winlator.xenvironment.ImageFs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public final class BasePrefix {
    private static final String TAG = "BasePrefix";
    private static final int BUILD_VERSION = 3;
    private static final int[] LEGACY_BUILD_VERSIONS = {1, 2};
    private static final java.util.List<String> BASE_DRIVES = java.util.Arrays.asList("c:", "z:");
    public static final String DIR_NAME = "base_prefix";
    static final String COMPLETE_MARKER = ".complete";
    static final String BUILDING_MARKER = ".building";
    private static final String STAGING_DIR = ".staging";
    private static final String OLD_DIR = ".wine.old";
    private static final Map<String, Object> LOCKS = new HashMap<>();

    private BasePrefix() {}

    private static Object lockFor(String key) {
        synchronized (LOCKS) {
            Object lock = LOCKS.get(key);
            if (lock == null) {
                lock = new Object();
                LOCKS.put(key, lock);
            }
            return lock;
        }
    }

    public static File getBaseDir(Context context, ContentsManager contentsManager, String wineVersion) {
        if (WineInfo.isMainWineVersion(wineVersion)) return null;
        WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
        if (wineInfo.path == null || wineInfo.path.isEmpty()) return null;
        return new File(wineInfo.path, DIR_NAME);
    }

    public static File ensure(Context context, ContentsManager contentsManager, String wineVersion) {
        File baseDir = getBaseDir(context, contentsManager, wineVersion);
        if (baseDir == null) {
            Log.w(TAG, "No base prefix location for wine version " + wineVersion);
            return null;
        }
        int imgVersion = ImageFs.find(context).getVersion();
        String identity = BUILD_VERSION + ":" + wineVersion + ":" + imgVersion;
        File wineDir = new File(baseDir, ".wine");
        synchronized (lockFor(baseDir.getAbsolutePath())) {
            if (isComplete(baseDir, identity)) return wineDir;
            for (int legacyVersion : LEGACY_BUILD_VERSIONS) {
                String legacyIdentity = legacyVersion + ":" + wineVersion + ":" + imgVersion;
                if (upgradeLegacyBase(baseDir, legacyIdentity, identity)) {
                    Log.i(TAG, "Normalized the existing base for " + wineVersion + " from version " + legacyVersion);
                    return wineDir;
                }
            }
            ContainerOverlay.splash("Preparing " + wineVersion + " base...");
            Log.i(TAG, "Building base prefix for " + wineVersion + " at " + baseDir);
            if (build(context, contentsManager, wineVersion, baseDir, identity)) return wineDir;
            if (wineDir.isDirectory() && new File(baseDir, COMPLETE_MARKER).isFile()) {
                Log.w(TAG, "Base prefix rebuild failed for " + wineVersion + ", keeping the previous base");
                return wineDir;
            }
            return null;
        }
    }

    static boolean isComplete(File baseDir, String identity) {
        File marker = new File(baseDir, COMPLETE_MARKER);
        if (!marker.isFile() || new File(baseDir, BUILDING_MARKER).exists()) return false;
        if (!new File(baseDir, ".wine").isDirectory()) return false;
        String content = FileUtils.readString(marker);
        return content != null && content.trim().equals(identity);
    }

    static boolean upgradeLegacyBase(File baseDir, String legacyIdentity, String identity) {
        if (!isComplete(baseDir, legacyIdentity)) return false;
        if (!normalize(new File(baseDir, ".wine"))) return false;
        return FileUtils.writeString(new File(baseDir, COMPLETE_MARKER), identity);
    }

    public static boolean normalize(File wineDir) {
        return removeAppManagedFiles(wineDir) & trimDosdevices(wineDir);
    }

    static boolean trimDosdevices(File wineDir) {
        File dosdevices = new File(wineDir, ContainerFiles.DOSDEVICES);
        File[] entries = dosdevices.listFiles();
        if (entries == null) return true;
        boolean ok = true;
        for (File entry : entries) {
            if (BASE_DRIVES.contains(entry.getName().toLowerCase())) continue;
            ok &= ContainerFiles.deleteRecursively(entry);
        }
        return ok & ensureRootDrive(wineDir);
    }

    public static boolean ensureRootDrive(File wineDir) {
        java.nio.file.Path link = new File(new File(wineDir, ContainerFiles.DOSDEVICES), "z:").toPath();
        try {
            if (java.nio.file.Files.isSymbolicLink(link) && "/".equals(java.nio.file.Files.readSymbolicLink(link).toString())) return true;
            java.nio.file.Files.deleteIfExists(link);
            java.nio.file.Files.createSymbolicLink(link, java.nio.file.Paths.get("/"));
            return true;
        }
        catch (java.io.IOException e) {
            Log.w(TAG, "Could not point z: at / in " + wineDir + ": " + e);
            return false;
        }
    }

    static boolean removeAppManagedFiles(File wineDir) {
        boolean ok = true;
        for (String rel : ContainerFiles.APP_MANAGED_FILES) {
            File file = new File(wineDir, rel);
            if (!ContainerFiles.exists(file)) continue;
            try {
                Files.delete(file.toPath());
            }
            catch (IOException e) {
                Log.w(TAG, "Failed to remove " + file + ": " + e);
                ok = false;
            }
        }
        return ok;
    }

    private static boolean build(Context context, ContentsManager contentsManager, String wineVersion, File baseDir, String identity) {
        File staging = new File(baseDir, STAGING_DIR);
        File building = new File(baseDir, BUILDING_MARKER);
        File wineDir = new File(baseDir, ".wine");
        File oldDir = new File(baseDir, OLD_DIR);
        try {
            if (!baseDir.getParentFile().isDirectory()) {
                Log.e(TAG, "Wine content for " + wineVersion + " is missing at " + baseDir.getParentFile());
                return false;
            }
            if (!baseDir.isDirectory() && !baseDir.mkdir()) return false;
            if (!FileUtils.writeString(building, identity)) {
                Log.e(TAG, "Failed to write the build marker at " + building);
                return false;
            }
            FileUtils.delete(staging);
            if (!staging.mkdirs()) return false;

            File stagingWine = new File(staging, ".wine");
            ContainerManager containerManager = new ContainerManager(context);
            if (!containerManager.extractContainerPatternFile(wineVersion, contentsManager, staging, null)) {
                Log.e(TAG, "Failed to extract the container pattern for " + wineVersion);
                FileUtils.delete(staging);
                building.delete();
                if (!wineDir.exists()) baseDir.delete();
                return false;
            }

            File commonRoot = new File(staging, "home/" + ImageFs.USER + "/.wine");
            String commonPrefix = commonRoot.getAbsolutePath();
            OnExtractFileListener commonRemap = (file, size) -> {
                String path = file.getAbsolutePath();
                if (!path.equals(commonPrefix) && !path.startsWith(commonPrefix + "/")) return null;
                return new File(stagingWine, path.substring(commonPrefix.length()));
            };
            if (!containerManager.extractContainerPatternCommon(staging, commonRemap)) {
                Log.w(TAG, "Failed to extract container_pattern_common into the base for " + wineVersion);
            }
            FileUtils.delete(new File(staging, "home"));

            if (!new File(stagingWine, "drive_c/windows").isDirectory()) {
                Log.e(TAG, "Base prefix for " + wineVersion + " has no drive_c/windows");
                FileUtils.delete(staging);
                building.delete();
                if (!wineDir.exists()) baseDir.delete();
                return false;
            }
            normalize(stagingWine);

            FileUtils.delete(oldDir);
            if (wineDir.exists() && !wineDir.renameTo(oldDir)) {
                Log.e(TAG, "Failed to move the previous base aside at " + wineDir);
                FileUtils.delete(staging);
                building.delete();
                if (!wineDir.exists()) baseDir.delete();
                return false;
            }
            if (!stagingWine.renameTo(wineDir)) {
                Log.e(TAG, "Failed to install the base prefix at " + wineDir);
                if (oldDir.exists()) oldDir.renameTo(wineDir);
                FileUtils.delete(staging);
                building.delete();
                if (!wineDir.exists()) baseDir.delete();
                return false;
            }
            FileUtils.writeString(new File(baseDir, COMPLETE_MARKER), identity);
            building.delete();
            FileUtils.delete(oldDir);
            FileUtils.delete(staging);
            Log.i(TAG, "Base prefix ready for " + wineVersion);
            return true;
        }
        catch (Throwable t) {
            Log.e(TAG, "Base prefix build failed for " + wineVersion, t);
            FileUtils.delete(staging);
            building.delete();
            return false;
        }
    }
}
