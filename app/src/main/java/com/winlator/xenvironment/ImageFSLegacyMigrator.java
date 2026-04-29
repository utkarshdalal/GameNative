package com.winlator.xenvironment;

import android.content.Context;
import android.util.Log;

import com.winlator.core.FileUtils;

import java.io.File;

public final class ImageFSLegacyMigrator {
    private ImageFSLegacyMigrator() {}

    public static boolean migrateLegacyDirsIfNeeded(Context context, File legacyImageFsRoot) {
        if (!migrateLegacyHomeToShared(context, legacyImageFsRoot)) {
            return false;
        }
        if (!migrateLegacyProtonToShared(context, legacyImageFsRoot)) {
            return false;
        }
        return true;
    }

    /**
     * Before deleting legacy files/imagefs, preserve /home contents by moving them into
     * files/imagefs_shared/home so first-run sync can reuse xuser/.wine safely.
     */
    private static boolean migrateLegacyHomeToShared(Context context, File legacyImageFsRoot) {
        File legacyHome = new File(legacyImageFsRoot, "home");
        File sharedHomeRoot = new File(ImageFs.getImageFsSharedDir(context), "home");

        if (FileUtils.isSymlink(legacyHome)) {
            // Already migrated: /imagefs/home is a symlink to imagefs_shared/home.
            return true;
        }

        if (!legacyHome.exists() || !legacyHome.isDirectory()) {
            // No need to migrate.
            return true;
        }

        if (sharedHomeRoot.exists()) {
            Log.w("ImageFSLegacyMigrator", "Shared home already exists; overwriting with legacy home migration.");
            FileUtils.delete(sharedHomeRoot);
        }

        if (!legacyHome.renameTo(sharedHomeRoot)) {
            Log.w("ImageFSLegacyMigrator", "Direct move failed for legacy home; falling back to copy+delete.");
            boolean copied = FileUtils.copy(legacyHome, sharedHomeRoot);
            if (copied) {
                FileUtils.delete(legacyHome);
                Log.i("ImageFSLegacyMigrator", "Migrated legacy home via copy+delete to: " + sharedHomeRoot.getAbsolutePath());
                return true;
            } else {
                Log.w("ImageFSLegacyMigrator", "Failed to migrate legacy home directory: " + legacyHome.getAbsolutePath());
                return false;
            }
        } else {
            Log.i("ImageFSLegacyMigrator", "Migrated legacy home via direct move to: " + sharedHomeRoot.getAbsolutePath());
            return true;
        }
    }

    /**
     * Before deleting legacy opt/proton-<version> directories, preserve them by moving them into
     * files/imagefs_shared/proton so they can be symlinked.
     */
    private static boolean migrateLegacyProtonToShared(Context context, File legacyImageFsRoot) {
        File optDir = new File(legacyImageFsRoot, "opt");
        File[] optEntries = optDir.listFiles();
        if (optEntries == null) {
            return true;
        }

        for (File entry : optEntries) {
            if (!entry.isDirectory() || FileUtils.isSymlink(entry) || !entry.getName().startsWith("proton-")) {
                continue;
            }

            File sharedProtonDir = new File(ImageFs.getSharedProtonDir(context), entry.getName());
            if (sharedProtonDir.exists()) {
                Log.w("ImageFSLegacyMigrator", "Shared Proton already exists; removing duplicate legacy opt entry: " + entry.getName());
                if (!FileUtils.delete(entry)) {
                    Log.w("ImageFSLegacyMigrator", "Failed to remove duplicate legacy Proton directory: " + entry.getAbsolutePath());
                    return false;
                }
                continue;
            }

            if (!entry.renameTo(sharedProtonDir)) {
                Log.w("ImageFSLegacyMigrator", "Direct move failed for Proton " + entry.getName() + "; falling back to copy+delete.");
                boolean copied = FileUtils.copy(entry, sharedProtonDir);
                if (copied) {
                    FileUtils.delete(entry);
                    Log.i("ImageFSLegacyMigrator", "Migrated Proton via copy+delete to: " + sharedProtonDir.getAbsolutePath());
                    continue;
                }
                Log.w("ImageFSLegacyMigrator", "Failed to migrate Proton directory: " + entry.getAbsolutePath());
                return false;
            }

            Log.i("ImageFSLegacyMigrator", "Migrated Proton via direct move to: " + sharedProtonDir.getAbsolutePath());
        }

        return true;
    }
}
