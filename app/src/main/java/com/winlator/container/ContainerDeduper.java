package com.winlator.container;

import android.content.Context;
import android.util.Log;

import com.winlator.contents.ContentsManager;
import com.winlator.core.FileUtils;
import com.winlator.core.WineInfo;
import com.winlator.xenvironment.ImageFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.Set;

public abstract class ContainerDeduper {
    private static final String TAG = "ContainerDeduper";
    private static final String EXTRA_KEY = "dedupeVersion";
    private static final String EXTRA_VALUE = "1";

    public static class DedupeResult {
        public int filesLinked;
        public long bytesSaved;
        public int filesSkippedModified;
        public boolean linkingUnsupported;
        public boolean completed;

        @Override
        public String toString() {
            return "linked=" + filesLinked + " bytesSaved=" + bytesSaved +
                   " skippedModified=" + filesSkippedModified +
                   " linkingUnsupported=" + linkingUnsupported + " completed=" + completed;
        }
    }

    public static boolean isDone(Container container) {
        try {
            return container != null && EXTRA_VALUE.equals(container.getExtra(EXTRA_KEY));
        }
        catch (Throwable t) {
            Log.w(TAG, "isDone failed", t);
            return false;
        }
    }

    public static void markDone(Container container) {
        try {
            if (container == null) return;
            container.putExtra(EXTRA_KEY, EXTRA_VALUE);
            container.saveData();
        }
        catch (Throwable t) {
            Log.w(TAG, "markDone failed", t);
        }
    }

    public static DedupeResult dedupe(Context context, ContentsManager contentsManager, Container container) {
        DedupeResult result = new DedupeResult();
        try {
            if (context == null || container == null) return result;
            File rootDir = container.getRootDir();
            if (rootDir == null || !rootDir.isDirectory()) return result;

            File system32 = new File(rootDir, ".wine/drive_c/windows/system32");
            File syswow64 = new File(rootDir, ".wine/drive_c/windows/syswow64");

            String wineVersion = container.getWineVersion();
            WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion);

            if (WineInfo.isMainWineVersion(wineVersion)) {
                File wineLib = new File(ImageFs.find(context).getRootDir(), "/opt/wine/lib/wine");
                Set<String> system32Names = readCommonDlls(context, "system32");
                Set<String> syswow64Names = readCommonDlls(context, "syswow64");
                dedupeDir(new File(wineLib, "x86_64-windows"), system32, system32Names, result);
                if (result.linkingUnsupported) return result;
                dedupeDir(new File(wineLib, "i386-windows"), syswow64, syswow64Names, result);
            }
            else {
                if (wineInfo == null || wineInfo.path == null) return result;
                File wineLib = new File(wineInfo.path + "/lib/wine");
                boolean arm64ec = wineInfo.isArm64EC();
                File i386Dir = new File(wineLib, "i386-windows");
                File mainDir = new File(wineLib, arm64ec ? "aarch64-windows" : "x86_64-windows");

                Set<String> mainNames = null;
                if (arm64ec) {
                    mainNames = new HashSet<>();
                    String[] listed = mainDir.list();
                    if (listed != null) mainNames.addAll(java.util.Arrays.asList(listed));
                    mainNames.remove("iexplore.exe");
                }
                dedupeDir(mainDir, system32, mainNames, result);
                if (result.linkingUnsupported) return result;
                if (arm64ec) {
                    dedupeFile(new File(i386Dir, "iexplore.exe"), new File(system32, "iexplore.exe"), result);
                    if (result.linkingUnsupported) return result;
                }
                dedupeDir(i386Dir, syswow64, null, result);
            }
            result.completed = !result.linkingUnsupported;
            Log.d(TAG, "dedupe finished for container " + container.id + ": " + result);
        }
        catch (Throwable t) {
            Log.w(TAG, "dedupe failed", t);
        }
        return result;
    }

    static void dedupeDir(File srcDir, File dstDir, Set<String> onlyNames, DedupeResult acc) {
        try {
            if (srcDir == null || dstDir == null || !srcDir.isDirectory() || !dstDir.isDirectory()) return;
            File[] dstFiles = dstDir.listFiles();
            if (dstFiles == null) return;

            for (File dstFile : dstFiles) {
                if (acc.linkingUnsupported) return;
                String name = dstFile.getName();
                if (onlyNames != null && !onlyNames.contains(name)) continue;
                dedupeFile(new File(srcDir, name), dstFile, acc);
            }
        }
        catch (Throwable t) {
            Log.w(TAG, "dedupeDir failed for " + dstDir, t);
        }
    }

    private static void dedupeFile(File srcFile, File dstFile, DedupeResult acc) {
        try {
            if (srcFile == null || dstFile == null) return;
            if (!srcFile.isFile() || !dstFile.isFile()) return;
            if (FileUtils.isSymlink(srcFile) || FileUtils.isSymlink(dstFile)) return;
            if (isSameFile(srcFile, dstFile)) return;

            long size = dstFile.length();
            if (srcFile.length() != size) {
                acc.filesSkippedModified++;
                return;
            }
            if (!FileUtils.contentEquals(srcFile, dstFile)) {
                acc.filesSkippedModified++;
                return;
            }

            File tmpFile = new File(dstFile.getParentFile(), "." + dstFile.getName() + ".dedupe.tmp");
            if (tmpFile.exists() && !tmpFile.delete()) return;

            try {
                Files.createLink(tmpFile.toPath(), srcFile.toPath());
            }
            catch (UnsupportedOperationException | java.io.IOException e) {
                Log.w(TAG, "hardlinking unsupported on this filesystem: " + e);
                acc.linkingUnsupported = true;
                //noinspection ResultOfMethodCallIgnored
                tmpFile.delete();
                return;
            }

            try {
                Files.move(tmpFile.toPath(), dstFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (Throwable t) {
                Log.w(TAG, "rename failed for " + dstFile + ": " + t);
                //noinspection ResultOfMethodCallIgnored
                tmpFile.delete();
                return;
            }

            chmodShared(srcFile);
            acc.filesLinked++;
            acc.bytesSaved += size;
        }
        catch (Throwable t) {
            Log.w(TAG, "dedupeFile failed for " + dstFile, t);
        }
    }

    private static void chmodShared(File file) {
        try {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("r-xr-xr-x"));
        }
        catch (Throwable t) {
            Log.w(TAG, "chmod failed for " + file + ": " + t);
        }
    }

    private static boolean isSameFile(File a, File b) {
        try {
            Path pa = a.toPath();
            Path pb = b.toPath();
            if (Files.isSameFile(pa, pb)) return true;
            BasicFileAttributes aa = Files.readAttributes(pa, BasicFileAttributes.class);
            BasicFileAttributes ab = Files.readAttributes(pb, BasicFileAttributes.class);
            Object ka = aa.fileKey();
            Object kb = ab.fileKey();
            return ka != null && ka.equals(kb);
        }
        catch (Throwable t) {
            return false;
        }
    }

    private static Set<String> readCommonDlls(Context context, String dstName) {
        Set<String> names = new HashSet<>();
        try {
            JSONObject commonDlls = new JSONObject(FileUtils.readString(context, "common_dlls.json"));
            JSONArray dllNames = commonDlls.getJSONArray(dstName);
            for (int i = 0; i < dllNames.length(); i++) names.add(dllNames.getString(i));
        }
        catch (Throwable t) {
            Log.w(TAG, "failed to read common_dlls.json", t);
        }
        return names;
    }
}
