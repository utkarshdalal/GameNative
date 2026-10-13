package com.winlator.container;

import android.content.Context;
import android.util.Log;

import com.winlator.core.WineInfo;
import com.winlator.core.envvars.EnvVars;
import com.winlator.xenvironment.ImageFs;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.gamenative.PluviaApp;
import app.gamenative.events.AndroidEvent;

public final class ContainerOverlay {
    private static final String TAG = "ContainerOverlay";

    public static final String OVERLAY_DIR = ".gnoverlay";
    public static final String WHITEOUT_DIR = "wh";
    public static final String OPAQUE_DIR = "opaque";
    public static final String BIONIC_LIB = "libgnoverlay.so";

    public static final String ENV_UPPER = "GN_OVERLAY_UPPER";
    public static final String ENV_LOWER = "GN_OVERLAY_LOWER";
    public static final String ENV_ALIASES = "GN_OVERLAY_ALIASES";
    public static final String ENV_DEBUG = "GN_OVERLAY_DEBUG";

    static final String[] REGISTRY_FILES = {"system.reg", "user.reg", "userdef.reg"};

    private static final String DATA_DATA = "/data/data/";
    private static final String DATA_USER_0 = "/data/user/0/";
    private static final String GUEST_WINE_PREFIX = "/home/" + ImageFs.USER + "/.wine";

    private ContainerOverlay() {}

    public static boolean isEligible(Container container) {
        return container != null
                && Container.BIONIC.equalsIgnoreCase(container.getContainerVariant())
                && !WineInfo.isMainWineVersion(container.getWineVersion());
    }

    public static File bionicLibFile(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir, BIONIC_LIB);
    }

    public static String normalizeDataPath(String path) {
        if (path == null) return null;
        if (path.startsWith(DATA_DATA)) return DATA_USER_0 + path.substring(DATA_DATA.length());
        return path;
    }

    public static String dataDataSpelling(String path) {
        if (path == null) return null;
        if (path.startsWith(DATA_USER_0)) return DATA_DATA + path.substring(DATA_USER_0.length());
        return path;
    }

    public static String canonicalHostPath(File file) {
        String path;
        try {
            path = file.getCanonicalPath();
        }
        catch (IOException e) {
            path = file.getAbsolutePath();
        }
        return normalizeDataPath(path);
    }

    public static List<String> aliases(String upper, String imageFsRoot, String imageFsShared) {
        Set<String> result = new LinkedHashSet<>();
        for (String root : new String[]{imageFsRoot, imageFsShared}) {
            if (root == null || root.isEmpty()) continue;
            String alias = normalizeDataPath(stripTrailingSlash(root) + GUEST_WINE_PREFIX);
            result.add(alias);
            result.add(dataDataSpelling(alias));
        }
        result.add(GUEST_WINE_PREFIX);
        if (upper != null) {
            result.add(dataDataSpelling(upper));
            result.remove(upper);
        }
        return new ArrayList<>(result);
    }

    public static Map<String, String> buildEnv(String upper, String lower, List<String> aliases, boolean debug) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(ENV_UPPER, upper);
        env.put(ENV_LOWER, lower);
        env.put(ENV_ALIASES, String.join(":", aliases));
        if (debug) env.put(ENV_DEBUG, "1");
        return env;
    }

    public static String appendPreload(String current, String lib, String separator) {
        if (current == null || current.trim().isEmpty()) return lib;
        for (String entry : current.split(java.util.regex.Pattern.quote(separator))) {
            if (entry.trim().equals(lib)) return current;
        }
        return current + separator + lib;
    }

    public static boolean applyBionicLaunchEnv(Context context, Container container, EnvVars envVars) {
        if (container == null || !container.isOverlay()) return false;
        File lib = bionicLibFile(context);
        if (!lib.isFile()) {
            throw new IllegalStateException("Overlay library missing, cannot launch thin container " + container.id);
        }
        File lower = new File(container.getBasePrefix());
        if (!lower.isDirectory()) {
            throw new IllegalStateException("Base prefix missing at " + lower + ", cannot launch thin container " + container.id);
        }
        ImageFs imageFs = ImageFs.find(context);
        String upperPath = canonicalHostPath(new File(container.getRootDir(), ".wine"));
        String lowerPath = canonicalHostPath(lower);
        List<String> aliasList = aliases(upperPath, imageFs.getRootDir().getAbsolutePath(),
                ImageFs.getImageFsSharedDir(context).getAbsolutePath());
        boolean debug = new EnvVars(container.getEnvVars()).has(ENV_DEBUG);

        envVars.put("LD_PRELOAD", appendPreload(envVars.get("LD_PRELOAD"), lib.getAbsolutePath(), ":"));
        for (Map.Entry<String, String> entry : buildEnv(upperPath, lowerPath, aliasList, debug).entrySet()) {
            envVars.put(entry.getKey(), entry.getValue());
        }
        if (!debug) envVars.remove(ENV_DEBUG);
        Log.i(TAG, "Overlay enabled for " + container.id + ": upper=" + upperPath + " lower=" + lowerPath);
        return true;
    }

    public static boolean createThinPrefix(File baseWine, File upperWine) {
        try {
            Files.createDirectories(upperWine.toPath());
            if (!copyTree(new File(baseWine, "dosdevices"), new File(upperWine, "dosdevices"), false)) return false;
            for (String name : REGISTRY_FILES) {
                File src = new File(baseWine, name);
                File dst = new File(upperWine, name);
                if (src.isFile() && !ContainerFiles.exists(dst)) copyFile(src.toPath(), dst.toPath());
            }
            if (!copyTree(new File(baseWine, "drive_c/users"), new File(upperWine, "drive_c/users"), false)) return false;
            Files.createDirectories(new File(upperWine, "drive_c/windows/temp").toPath());
            Files.createDirectories(new File(upperWine, OVERLAY_DIR).toPath());
            Files.createDirectories(new File(upperWine, ContainerFiles.DOSDEVICES).toPath());
            return ContainerFiles.markOpaque(upperWine, ContainerFiles.DOSDEVICES);
        }
        catch (IOException e) {
            Log.w(TAG, "createThinPrefix failed for " + upperWine + ": " + e);
            return false;
        }
    }

    public static boolean copyTree(File src, File dst, boolean overwrite) {
        if (!ContainerFiles.exists(src)) return true;
        final Path srcRoot = src.toPath();
        final Path dstRoot = dst.toPath();
        try {
            Files.walkFileTree(srcRoot, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path target = dstRoot.resolve(srcRoot.relativize(dir).toString());
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        return Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(target);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path target = dstRoot.resolve(srcRoot.relativize(file).toString());
                    boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
                    if (exists && (!overwrite || Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))) return FileVisitResult.CONTINUE;
                    if (attrs.isSymbolicLink()) {
                        Files.deleteIfExists(target);
                        Files.createSymbolicLink(target, Files.readSymbolicLink(file));
                    }
                    else if (attrs.isRegularFile()) {
                        copyFile(file, target);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return true;
        }
        catch (IOException e) {
            Log.w(TAG, "copyTree failed " + src + " -> " + dst + ": " + e);
            return false;
        }
    }

    static void copyFile(Path src, Path dst) throws IOException {
        Files.deleteIfExists(dst);
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        dst.toFile().setWritable(true, true);
    }

    static void splash(String text) {
        try {
            PluviaApp.events.emitJava(new AndroidEvent.SetBootingSplashText(text));
        }
        catch (Throwable ignored) {
        }
    }

    private static String stripTrailingSlash(String path) {
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
