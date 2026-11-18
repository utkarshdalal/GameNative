package com.winlator.container;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ContainerFiles {
    public static final List<String> APP_MANAGED_FILES = Collections.unmodifiableList(Arrays.asList(
            "drive_c/windows/system32/lsteamclient.dll",
            "drive_c/windows/syswow64/lsteamclient.dll"
    ));

    public static final String DOSDEVICES = "dosdevices";

    private ContainerFiles() {}

    public static boolean markOpaque(File upper, String relPathUnderWine) {
        return writeMarker(new File(new File(new File(upper, ContainerOverlay.OVERLAY_DIR), ContainerOverlay.OPAQUE_DIR), normalize(relPathUnderWine)));
    }

    public static boolean isAppManaged(String relPathUnderWine) {
        String rel = normalize(relPathUnderWine);
        for (String managed : APP_MANAGED_FILES) {
            if (managed.equalsIgnoreCase(rel)) return true;
        }
        return false;
    }

    public static File upperDir(Container container) {
        return new File(container.getRootDir(), ".wine");
    }

    public static File lowerDir(Container container) {
        if (container == null || !container.isOverlay()) return null;
        return new File(container.getBasePrefix());
    }

    public static File resolve(Container container, String relPathUnderWine) {
        if (container == null || container.getRootDir() == null) return null;
        return resolve(upperDir(container), lowerDir(container), relPathUnderWine);
    }

    public static File resolve(File upper, File lower, String relPathUnderWine) {
        String rel = normalize(relPathUnderWine);
        File upperFile = rel.isEmpty() ? upper : new File(upper, rel);
        if (exists(upperFile)) return upperFile;
        if (lower == null || isHidden(upper, rel)) return null;
        File lowerFile = rel.isEmpty() ? lower : new File(lower, rel);
        return exists(lowerFile) ? lowerFile : null;
    }

    public static boolean isHidden(File upper, String relPathUnderWine) {
        String rel = normalize(relPathUnderWine);
        if (rel.isEmpty()) return false;
        File overlayDir = new File(upper, ContainerOverlay.OVERLAY_DIR);
        File whiteoutDir = new File(overlayDir, ContainerOverlay.WHITEOUT_DIR);
        File opaqueDir = new File(overlayDir, ContainerOverlay.OPAQUE_DIR);
        String[] parts = rel.split("/");
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) prefix.append('/');
            prefix.append(parts[i]);
            String path = prefix.toString();
            if (isMarker(new File(whiteoutDir, path))) return true;
            if (i < parts.length - 1 && isMarker(new File(opaqueDir, path))) return true;
        }
        return false;
    }

    public static File whiteoutFile(File upper, String relPathUnderWine) {
        return new File(new File(new File(upper, ContainerOverlay.OVERLAY_DIR), ContainerOverlay.WHITEOUT_DIR), normalize(relPathUnderWine));
    }

    public static boolean removeWhiteout(File upper, String relPathUnderWine) {
        File whiteout = whiteoutFile(upper, relPathUnderWine);
        if (!isMarker(whiteout)) return true;
        try {
            Files.delete(whiteout.toPath());
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }

    public static boolean deleteWithWhiteout(Container container, String relPathUnderWine) {
        if (container == null || container.getRootDir() == null) return false;
        String rel = normalize(relPathUnderWine);
        if (rel.isEmpty()) return false;
        File upper = upperDir(container);
        File upperFile = new File(upper, rel);
        boolean ok = !exists(upperFile) || deleteRecursively(upperFile);
        File lower = lowerDir(container);
        if (lower == null) return ok;
        if (!exists(new File(lower, rel)) || isHidden(upper, rel)) return ok;
        return setWhiteout(upper, rel) && ok;
    }

    static boolean setWhiteout(File upper, String relPathUnderWine) {
        String rel = normalize(relPathUnderWine);
        File overlayDir = new File(upper, ContainerOverlay.OVERLAY_DIR);
        deleteRecursively(new File(new File(overlayDir, ContainerOverlay.OPAQUE_DIR), rel));
        return writeMarker(new File(new File(overlayDir, ContainerOverlay.WHITEOUT_DIR), rel));
    }

    private static boolean writeMarker(File marker) {
        if (isMarker(marker)) return true;
        if (exists(marker) && !deleteRecursively(marker)) return false;
        try {
            Files.createDirectories(marker.getParentFile().toPath());
            Files.createFile(marker.toPath());
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }

    static boolean isMarker(File file) {
        return Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    public static boolean removeOverride(File upper, String relPathUnderWine) {
        String rel = normalize(relPathUnderWine);
        if (rel.isEmpty()) return false;
        File upperFile = new File(upper, rel);
        boolean ok = true;
        if (exists(upperFile) && !Files.isDirectory(upperFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.delete(upperFile.toPath());
            }
            catch (IOException e) {
                ok = false;
            }
        }
        return removeWhiteout(upper, rel) && ok;
    }

    static boolean exists(File file) {
        return Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    static boolean deleteRecursively(File file) {
        if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (!file.canWrite()) file.setWritable(true, true);
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) return false;
                }
            }
        }
        try {
            Files.deleteIfExists(file.toPath());
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }

    static String normalize(String relPath) {
        if (relPath == null) return "";
        String rel = relPath.replace('\\', '/');
        while (rel.contains("//")) rel = rel.replace("//", "/");
        while (rel.startsWith("/")) rel = rel.substring(1);
        while (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
        return rel;
    }
}
