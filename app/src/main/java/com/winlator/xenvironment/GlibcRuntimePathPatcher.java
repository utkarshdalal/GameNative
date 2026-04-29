package com.winlator.xenvironment;

import android.content.Context;
import android.util.Log;

import com.winlator.container.Container;
import com.winlator.core.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class GlibcRuntimePathPatcher {
    private static final String TAG = "GlibcRuntimePathPatcher";
    private static final String IMAGEFS_ALIAS = "imgfs";

    private static final String[] PATCHED_RUNTIME_FILES = {
        "usr/lib/libX11.so",
        "usr/lib/libX11.so.6",
        "usr/lib/libX11.so.6.4.0",
        "usr/lib/libxcb.so",
        "usr/lib/libxcb.so.1",
        "usr/lib/libxcb.so.1.1.0",
        "usr/lib/libredirect.so",
    };

    private GlibcRuntimePathPatcher() {
    }

    public static void patch(Context context, ImageFs imageFs, String containerVariant) {
        if (!Container.GLIBC.equals(containerVariant)) {
            return;
        }

        ensureImageFsAlias(context);

        int patched = 0;
        for (String relativePath : PATCHED_RUNTIME_FILES) {
            patched += patchFileForPackage(new File(imageFs.getRootDir(), relativePath), context.getPackageName());
        }

        if (patched > 0) {
            Log.i(TAG, "Patched " + patched + " glibc runtime path reference(s)");
        }
    }

    private static void ensureImageFsAlias(Context context) {
        File alias = new File(context.getDataDir(), IMAGEFS_ALIAS);
        if (Files.isSymbolicLink(alias.toPath())) {
            return;
        }

        if (alias.exists() && !FileUtils.delete(alias)) {
            Log.w(TAG, "Unable to replace existing imagefs alias: " + alias.getPath());
            return;
        }

        FileUtils.symlink("files/imagefs", alias.getPath());
    }

    static int patchFileForPackage(File file, String packageName) {
        if (!file.isFile() || Files.isSymbolicLink(file.toPath())) {
            return 0;
        }

        byte[] data;
        try {
            data = Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            Log.w(TAG, "Unable to read " + file.getPath(), e);
            return 0;
        }

        int patched = patchBytesForPackage(data, packageName);
        if (patched == 0) {
            return 0;
        }

        try {
            Files.write(file.toPath(), data);
            FileUtils.chmod(file, 0755);
        } catch (IOException e) {
            Log.e(TAG, "Unable to write patched runtime file " + file.getPath(), e);
            return 0;
        }
        return patched;
    }

    static int patchBytesForPackage(byte[] data, String packageName) {
        String aliasPath = "/data/data/" + packageName + "/" + IMAGEFS_ALIAS;
        String aliasRelativePath = packageName + "/" + IMAGEFS_ALIAS;

        List<Replacement> replacements = new ArrayList<>();
        replacements.add(new Replacement(
            "/data/data/com.winlator/files/imagefs/usr/tmp/.X11-unix/X",
            aliasPath + "/usr/tmp/.X11-unix/X"
        ));
        replacements.add(new Replacement(
            "/data/data/com.winlator/files/imagefs/usr/tmp/.XIM-unix/XIM",
            aliasPath + "/usr/tmp/.XIM-unix/XIM"
        ));
        replacements.add(new Replacement(
            "/data/data/com.winlator/files/imagefs/usr/share/X11/XErrorDB",
            aliasPath + "/usr/share/X11/XErrorDB"
        ));
        replacements.add(new Replacement(
            "/data/data/com.winlator/files/imagefs/usr/share/X11/XKeysymDB",
            aliasPath + "/usr/share/X11/XKeysymDB"
        ));
        replacements.add(new Replacement(
            "/data/data/com.winlator/files/imagefs/usr/share/X11/locale",
            aliasPath + "/usr/share/X11/locale"
        ));
        replacements.add(new Replacement(
            "/data/data/com.winlator/files/imagefs/usr/lib/X11/locale",
            aliasPath + "/usr/lib/X11/locale"
        ));
        replacements.add(new Replacement(
            "/data/data/com.winlator/files/imagefs/usr/share/X11/Xcms.txt",
            aliasPath + "/usr/share/X11/Xcms.txt"
        ));
        replacements.add(new Replacement(
            "/data/data/com.winlator/files/imagefs/usr/lib",
            aliasPath + "/usr/lib"
        ));
        replacements.add(new Replacement(
            "LD_PRELOAD=/data/data/app.gamenative/files/imagefs/libpluviagoldberg.so",
            "LD_PRELOAD=" + aliasPath + "/libpluviagoldberg.so"
        ));
        replacements.add(new Replacement(
            "/data/data/app.gamenative/files/imagefs/preload_loaded.txt",
            aliasPath + "/preload_loaded.txt"
        ));
        replacements.add(new Replacement(
            "/data/data/app.gamenative/files/imagefs/usr/tmp",
            aliasPath + "/usr/tmp"
        ));
        replacements.add(new Replacement(
            "app.gamenative/files/imagefs",
            aliasRelativePath
        ));

        replacements.sort(Comparator.comparingInt((Replacement replacement) -> replacement.oldValue.length()).reversed());

        int patched = 0;
        for (Replacement replacement : replacements) {
            patched += replaceNullTerminatedAscii(data, replacement.oldValue, replacement.newValue);
        }
        return patched;
    }

    private static int replaceNullTerminatedAscii(byte[] data, String oldValue, String newValue) {
        byte[] oldBytes = oldValue.getBytes(StandardCharsets.US_ASCII);
        byte[] newBytes = newValue.getBytes(StandardCharsets.US_ASCII);

        int patched = 0;
        int start = 0;
        while (start <= data.length - oldBytes.length) {
            int index = indexOf(data, oldBytes, start);
            if (index < 0) {
                break;
            }

            int end = index + oldBytes.length;
            if (end < data.length && data[end] != 0) {
                start = index + 1;
                continue;
            }

            int capacityEnd = end;
            while (capacityEnd < data.length && data[capacityEnd] == 0) {
                capacityEnd++;
            }

            int capacity = capacityEnd - index;
            if (newBytes.length + 1 > capacity) {
                start = index + 1;
                continue;
            }

            System.arraycopy(newBytes, 0, data, index, newBytes.length);
            for (int i = index + newBytes.length; i < index + capacity; i++) {
                data[i] = 0;
            }
            patched++;
            start = index + capacity;
        }

        return patched;
    }

    private static int indexOf(byte[] data, byte[] needle, int start) {
        outer:
        for (int i = start; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static final class Replacement {
        private final String oldValue;
        private final String newValue;

        private Replacement(String oldValue, String newValue) {
            this.oldValue = oldValue;
            this.newValue = newValue;
        }
    }
}
