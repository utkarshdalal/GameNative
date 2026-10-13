package com.winlator.container;

import android.content.Context;
import android.util.Log;

import com.winlator.contents.ContentsManager;
import com.winlator.core.FileUtils;
import com.winlator.core.WineInfo;

import java.io.File;
import java.nio.file.StandardCopyOption;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

public final class ContainerOverlayMigrator {
    private static final String TAG = "ContainerOverlayMigrator";
    private static final String LEGACY_DEDUPE_EXTRA = "dedupeVersion";
    static final String[] PRUNE_ROOTS = {
            "drive_c/windows",
            "drive_c/Program Files",
            "drive_c/Program Files (x86)",
            "drive_c/ProgramData",
    };

    public static class Result {
        public int filesPruned;
        public long bytesFreed;
        public int filesKept;
        public boolean completed;

        @Override
        public String toString() {
            return "filesPruned=" + filesPruned + " bytesFreed=" + bytesFreed +
                   " filesKept=" + filesKept + " completed=" + completed;
        }
    }

    private ContainerOverlayMigrator() {}

    public static boolean migrateIfNeeded(Context context, ContentsManager contentsManager, Container container) {
        try {
            if (context == null || container == null || container.getRootDir() == null) return true;
            if (!ContainerOverlay.isEligible(container)) return true;
            boolean thin = !container.getBasePrefix().isEmpty();
            if (!thin && !ContainerOverlay.bionicLibFile(context).isFile()) {
                Log.e(TAG, "Overlay library missing, container " + container.id + " stays a full prefix");
                return true;
            }

            File baseWine = BasePrefix.ensure(context, contentsManager, container.getWineVersion());
            if (baseWine == null) {
                Log.e(TAG, "No base prefix for " + container.getWineVersion() + ", container " + container.id);
                return !thin;
            }

            if (!thin) {
                ContainerOverlay.splash("Migrating Wine prefix...");
                migrate(container, baseWine, protonLibDirs(context, contentsManager, container.getWineVersion()));
                return true;
            }

            ContainerFiles.markOpaque(new File(container.getRootDir(), ".wine"), ContainerFiles.DOSDEVICES);
            String basePath = ContainerOverlay.canonicalHostPath(baseWine);
            if (!basePath.equals(container.getBasePrefix())) {
                refreshSkeleton(new File(container.getRootDir(), ".wine"), baseWine);
                container.setBasePrefix(basePath);
                container.saveData();
            }
        }
        catch (Throwable t) {
            Log.w(TAG, "migrateIfNeeded failed", t);
        }
        return true;
    }

    static void refreshSkeleton(File upperWine, File baseWine) throws IOException {
        ContainerOverlay.copyTree(new File(baseWine, ContainerFiles.DOSDEVICES), new File(upperWine, ContainerFiles.DOSDEVICES), true);
        for (String name : ContainerOverlay.REGISTRY_FILES) {
            File src = new File(baseWine, name);
            if (src.isFile()) Files.copy(src.toPath(), new File(upperWine, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        dropStaleBuiltins(upperWine, baseWine);
    }

    static final String[] BUILTIN_DIRS = {"drive_c/windows/system32", "drive_c/windows/syswow64"};
    private static final byte[] BUILTIN_MARKER = "Wine builtin DLL".getBytes();

    static int dropStaleBuiltins(File upperWine, File baseWine) {
        int dropped = 0;
        for (String dir : BUILTIN_DIRS) {
            File[] files = new File(upperWine, dir).listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (!file.isFile()) continue;
                String rel = dir + "/" + file.getName();
                if (ContainerFiles.isAppManaged(rel) || !new File(baseWine, rel).isFile()) continue;
                if (!isWineBuiltin(file)) continue;
                if (file.delete()) dropped++;
            }
        }
        if (dropped > 0) Log.i(TAG, "Dropped " + dropped + " stale Wine builtins from " + upperWine);
        return dropped;
    }

    static boolean isWineBuiltin(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".json") || name.endsWith(".nls") || name.endsWith(".inf")) return true;
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] head = new byte[4096];
            int n = in.read(head);
            if (n < 2 || head[0] != 'M' || head[1] != 'Z') return false;
            outer:
            for (int i = 0; i + BUILTIN_MARKER.length <= n; i++) {
                for (int j = 0; j < BUILTIN_MARKER.length; j++) {
                    if (head[i + j] != BUILTIN_MARKER[j]) continue outer;
                }
                return true;
            }
        }
        catch (IOException e) {
            return false;
        }
        return false;
    }

    public static Result migrate(Container container, File baseWine, List<File> protonLibDirs) {
        File upper = new File(container.getRootDir(), ".wine");
        Result result = prune(upper, baseWine, protonLibDirs);
        Log.i(TAG, "Overlay migration for container " + container.id + ": " + result);
        if (result.completed) {
            ContainerFiles.deleteRecursively(new File(container.getRootDir(), ".cache/original_dlls"));
            new File(upper, ContainerOverlay.OVERLAY_DIR).mkdirs();
            ContainerFiles.markOpaque(upper, ContainerFiles.DOSDEVICES);
            container.setBasePrefix(ContainerOverlay.canonicalHostPath(baseWine));
            container.putExtra(LEGACY_DEDUPE_EXTRA, null);
            container.saveData();
        }
        return result;
    }

    static Result prune(File upper, File baseWine, List<File> protonLibDirs) {
        final Result result = new Result();
        final Path upperRoot = upper.toPath();
        final Path baseRoot = baseWine.toPath();
        try {
            for (String root : PRUNE_ROOTS) {
                Path start = upperRoot.resolve(root);
                if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) continue;
                Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                        String rel = upperRoot.relativize(file).toString();
                        if (ContainerFiles.isAppManaged(rel)) {
                            result.filesKept++;
                            return FileVisitResult.CONTINUE;
                        }
                        Path baseFile = baseRoot.resolve(rel);
                        BasicFileAttributes baseAttrs;
                        try {
                            baseAttrs = Files.readAttributes(baseFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        }
                        catch (IOException e) {
                            baseAttrs = null;
                        }
                        if (baseAttrs == null || !baseAttrs.isRegularFile()) {
                            result.filesKept++;
                            return FileVisitResult.CONTINUE;
                        }
                        int links = linkCount(file);
                        boolean prunable = (links > 1 && isLinkedInto(file, protonLibDirs))
                                || (attrs.size() == baseAttrs.size() && FileUtils.contentEquals(file.toFile(), baseFile.toFile()));
                        if (!prunable) {
                            result.filesKept++;
                            return FileVisitResult.CONTINUE;
                        }
                        try {
                            Files.delete(file);
                            result.filesPruned++;
                            if (links <= 1) result.bytesFreed += attrs.size();
                        }
                        catch (IOException e) {
                            result.filesKept++;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            result.completed = true;
        }
        catch (IOException e) {
            result.completed = false;
        }
        return result;
    }

    static List<File> protonLibDirs(Context context, ContentsManager contentsManager, String wineVersion) {
        List<File> dirs = new ArrayList<>();
        WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
        if (wineInfo.path == null || wineInfo.path.isEmpty()) return dirs;
        File wineLib = new File(wineInfo.path, "lib/wine");
        dirs.add(new File(wineLib, "aarch64-windows"));
        dirs.add(new File(wineLib, "x86_64-windows"));
        dirs.add(new File(wineLib, "i386-windows"));
        return dirs;
    }

    private static boolean isLinkedInto(Path file, List<File> dirs) {
        if (dirs == null) return false;
        String name = file.getFileName().toString();
        for (File dir : dirs) {
            Path candidate = new File(dir, name).toPath();
            try {
                if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(candidate, file)) return true;
            }
            catch (IOException ignored) {
            }
        }
        return false;
    }

    private static int linkCount(Path file) {
        try {
            return ((Number) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).intValue();
        }
        catch (Throwable t) {
            return 1;
        }
    }
}
