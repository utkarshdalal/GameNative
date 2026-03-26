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
}
