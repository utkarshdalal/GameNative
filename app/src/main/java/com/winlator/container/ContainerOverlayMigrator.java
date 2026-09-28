package com.winlator.container;

import android.content.Context;
import android.util.Log;

import com.winlator.contents.ContentsManager;
import com.winlator.core.FileUtils;
import com.winlator.core.WineInfo;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
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

    public static void migrateIfNeeded(Context context, ContentsManager contentsManager, Container container) {
        try {
            if (context == null || container == null || container.getRootDir() == null) return;
            boolean thin = !container.getBasePrefix().isEmpty();
            boolean eligible = ContainerOverlay.isEligible(container);
            boolean requested = ContainerOverlay.isRequested(container);
            boolean libPresent = ContainerOverlay.bionicLibFile(context).isFile();

            if (!eligible || !requested || !libPresent) {
                if (eligible && requested) Log.w(TAG, "Overlay library missing, container " + container.id + " stays a full prefix");
                if (thin) {
                    ContainerOverlay.splash("Restoring Wine prefix...");
                    rematerialize(container);
                }
                return;
            }

            File baseWine = BasePrefix.ensure(context, contentsManager, container.getWineVersion());
            if (baseWine == null) {
                Log.e(TAG, "No base prefix for " + container.getWineVersion() + ", container " + container.id);
                return;
            }

            if (!thin) {
                ContainerOverlay.splash("Migrating Wine prefix...");
                migrate(container, baseWine, protonLibDirs(context, contentsManager, container.getWineVersion()));
                return;
            }

            ContainerFiles.markOpaque(new File(container.getRootDir(), ".wine"), ContainerFiles.DOSDEVICES);
            String basePath = ContainerOverlay.canonicalHostPath(baseWine);
            if (!basePath.equals(container.getBasePrefix())) {
                container.setBasePrefix(basePath);
                container.saveData();
            }
        }
        catch (Throwable t) {
            Log.w(TAG, "migrateIfNeeded failed", t);
        }
    }

    public static Result migrate(Container container, File baseWine, List<File> protonLibDirs) {
        File upper = new File(container.getRootDir(), ".wine");
        Result result = prune(upper, baseWine, protonLibDirs);
        Log.i(TAG, "Overlay migration for container " + container.id + ": " + result);
        if (result.completed) {
            new File(upper, ContainerOverlay.OVERLAY_DIR).mkdirs();
            ContainerFiles.markOpaque(upper, ContainerFiles.DOSDEVICES);
            container.setBasePrefix(ContainerOverlay.canonicalHostPath(baseWine));
            container.setOverlay(true);
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

    public static boolean rematerialize(Container container) {
        if (container == null || container.getRootDir() == null) return false;
        String basePrefix = container.getBasePrefix();
        File upper = new File(container.getRootDir(), ".wine");
        File lower = basePrefix.isEmpty() ? null : new File(basePrefix);
        if (lower == null || !lower.isDirectory()) {
            Log.e(TAG, "Cannot restore container " + container.id + ": base prefix missing at " + basePrefix);
            return false;
        }
        boolean ok = rematerialize(upper, lower);
        Log.i(TAG, "Restored full prefix for container " + container.id + ": ok=" + ok);
        if (ok) {
            container.setBasePrefix("");
            container.setOverlay(false);
            container.saveData();
        }
        return ok;
    }

    static boolean rematerialize(File upper, File lower) {
        final Path upperRoot = upper.toPath();
        final Path lowerRoot = lower.toPath();
        try {
            Files.createDirectories(upperRoot);
            Files.walkFileTree(lowerRoot, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    String rel = lowerRoot.relativize(dir).toString();
                    if (rel.isEmpty()) return FileVisitResult.CONTINUE;
                    if (ContainerFiles.isHidden(upper, rel)) return FileVisitResult.SKIP_SUBTREE;
                    Path target = upperRoot.resolve(rel);
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        return Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectory(target);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String rel = lowerRoot.relativize(file).toString();
                    if (ContainerFiles.isHidden(upper, rel)) return FileVisitResult.CONTINUE;
                    Path target = upperRoot.resolve(rel);
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return FileVisitResult.CONTINUE;
                    if (attrs.isSymbolicLink()) {
                        Files.createSymbolicLink(target, Files.readSymbolicLink(file));
                    }
                    else if (attrs.isRegularFile()) {
                        ContainerOverlay.copyFile(file, target);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        catch (IOException e) {
            Log.w(TAG, "rematerialize failed for " + upper + ": " + e);
            return false;
        }
        return ContainerFiles.deleteRecursively(new File(upper, ContainerOverlay.OVERLAY_DIR));
    }

    public static boolean resetFromBase(Context context, ContentsManager contentsManager, Container container) {
        File baseWine = BasePrefix.ensure(context, contentsManager, container.getWineVersion());
        if (baseWine == null) {
            Log.e(TAG, "No base prefix for " + container.getWineVersion() + ", cannot reset container " + container.id);
            return false;
        }
        container.setBasePrefix(ContainerOverlay.canonicalHostPath(baseWine));
        container.saveData();
        return resetUpper(new File(container.getRootDir(), ".wine"), baseWine, BasePrefix.readPatternFiles(baseWine));
    }

    static boolean resetUpper(File upper, File baseWine, Collection<String> patternFiles) {
        for (String rel : patternFiles) {
            String normalized = ContainerFiles.normalize(rel);
            if (normalized.isEmpty()) continue;
            File file = new File(upper, normalized);
            if (ContainerFiles.exists(file) && !Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.delete(file.toPath());
                }
                catch (IOException e) {
                    Log.w(TAG, "Failed to reset " + file + ": " + e);
                }
            }
            ContainerFiles.removeWhiteout(upper, normalized);
        }
        return ContainerOverlay.createThinPrefix(baseWine, upper);
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
