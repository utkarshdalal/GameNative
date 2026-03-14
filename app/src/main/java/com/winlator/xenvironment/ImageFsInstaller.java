package com.winlator.xenvironment;

import static com.winlator.core.FileUtils.chmod;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import app.gamenative.R;
import app.gamenative.enums.Marker;
import app.gamenative.service.SteamService;
import app.gamenative.utils.ContainerUtils;
import app.gamenative.utils.MarkerUtils;

// import com.winlator.MainActivity;
// import com.winlator.R;
// import com.winlator.SettingsFragment;
import com.winlator.PrefManager;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
// import com.winlator.core.DownloadProgressDialog;
import com.winlator.contents.ContentsManager;
import com.winlator.core.Callback;
import com.winlator.core.DefaultVersion;
import com.winlator.core.FileUtils;
// import com.winlator.core.PreloaderDialog;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.WineInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public abstract class ImageFsInstaller {
    public static final byte LATEST_VERSION = 26;

    private static void resetContainerImgVersions(Context context) {
        ContainerManager manager = new ContainerManager(context);
        for (Container container : manager.getContainers()) {
            String imgVersion = container.getExtra("imgVersion");
            String wineVersion = container.getWineVersion();
            if (!imgVersion.isEmpty() && WineInfo.isMainWineVersion(wineVersion) && Short.parseShort(imgVersion) <= 5) {
                container.putExtra("wineprefixNeedsUpdate", "t");
            }

            container.putExtra("imgVersion", null);
            container.putExtra("dxwrapper", null);
            container.putExtra("appVersion", null);
            container.saveData();
        }
    }

    public static void installWineFromAssets(final Context context, AssetManager assetManager) {
        String[] versions = context.getResources().getStringArray(R.array.bionic_wine_entries);
        File protonDir = ImageFs.getSharedProtonDir(context);
        for (String version : versions) {
            File outFile = new File(protonDir, version);
            outFile.mkdirs();
            TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, assetManager, version + ".txz", outFile);
        }
    }

    public static void installWineFromDownloads(final Context context, File rootDir) {
        String[] versions = context.getResources().getStringArray(R.array.bionic_wine_entries);
        File downloadsDir = context.getFilesDir();
        File protonDir = ImageFs.getSharedProtonDir(context);
        for (String version : versions) {
            File downloaded = new File(downloadsDir, version + ".txz");
            if (!downloaded.exists()) continue;
            File outFile = new File(protonDir, version);
            if (outFile.exists() && outFile.isDirectory()) {
                String[] listing = outFile.list();
                if (listing != null && listing.length > 0) continue;
            }
            outFile.mkdirs();
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.XZ,
                downloaded,
                outFile
            );
        }
    }

    private static Future<Boolean> installFromAssetsFuture(final Context context, AssetManager assetManager, String containerVariant, Callback<Integer> onProgress) {
        // AppUtils.keepScreenOn(context);
        final File rootDir = ImageFs.getVariantRootDir(context, containerVariant);
        rootDir.getParentFile().mkdirs();
        if (!rootDir.exists()) rootDir.mkdirs();

        PrefManager.init(context);
        PrefManager.putString("current_box64_version", "");

        // final DownloadProgressDialog dialog = new DownloadProgressDialog(context);
        // dialog.show(R.string.installing_system_files);
        return Executors.newSingleThreadExecutor().submit(() -> {
            ImageFs.ensureImageFsSymlink(context, containerVariant);
            ensureSharedHomeRoot(context, rootDir);

            final byte compressionRatio = 22;
            String imagefsFile = containerVariant.equals(Container.GLIBC) ? "imagefs_gamenative.txz" : "imagefs_bionic.txz";
            File downloaded = new File(context.getFilesDir(), imagefsFile);

            boolean success = false;

            if (Arrays.asList(context.getAssets().list("")).contains(imagefsFile) == true){
                final long contentLength = (long) (FileUtils.getSize(assetManager, imagefsFile) * (100.0f / compressionRatio));
                AtomicLong totalSizeRef = new AtomicLong();
                Log.d("Extraction", "extracting " + imagefsFile + " to " + rootDir.getPath());

                success = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, assetManager, imagefsFile, rootDir, (file, size) -> {
                    if (size > 0) {
                        long totalSize = totalSizeRef.addAndGet(size);
                        if (onProgress != null) {
                            final int progress = (int) (((float) totalSize / contentLength) * 100);
                            onProgress.call(progress);
                        }
                    }
                    return file;
                });
            }

            else if (downloaded.exists()){
                final long contentLength = (long) (FileUtils.getSize(downloaded) * (100.0f / compressionRatio));
                AtomicLong totalSizeRef = new AtomicLong();
                Log.d("Extraction", "extracting " + imagefsFile + " to " + rootDir.getPath());
                success = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, downloaded, rootDir, (file, size) -> {
                    if (size > 0) {
                        long totalSize = totalSizeRef.addAndGet(size);
                        if (onProgress != null) {
                            final int progress = (int) (((float) totalSize / contentLength) * 100);
                            onProgress.call(progress);
                        }
                    }
                    return file;
                });
            }

            if (success) {
                Log.d("ImageFsInstaller", "Successfully installed system files for " + containerVariant);
                ContainerManager containerManager = new ContainerManager(context);

                installGuestLibs(context, rootDir);
                ImageFs.find(rootDir).createImgVersionFile(LATEST_VERSION);
                ImageFs.find(rootDir).createVariantFile(containerVariant);
                resetContainerImgVersions(context);

                if (containerVariant.equals(Container.BIONIC)) {
                    installWineFromDownloads(context, rootDir);
                    ensureSharedProtonDir(context, rootDir);
                }

                // Clear Steam DLL markers for all games
                clearSteamDllMarkers(context, containerManager);
            }
            else {
                Log.e("ImageFsInstaller", "Failed to install system files");
                if (downloaded.exists()) {
                    Log.w("ImageFsInstaller", "Deleting corrupt archive so next attempt re-downloads: " + downloaded.getPath());
                    downloaded.delete();
                }
            }
            return success;
            // dialog.closeOnUiThread();
        });
    }

    private static void installGuestLibs(Context ctx, File variantRoot) {
        final String ASSET_TAR = "redirect.tzst";          // ➊  add this to assets/
        // ➋  Unpack straight into imagefs, preserving relative paths.
        try (InputStream in  = ctx.getAssets().open(ASSET_TAR)) {
            TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD,      // you said .tzst
                    in, variantRoot);                  // helper already exists in the project
        } catch (IOException e) {
            Log.e("ImageFsInstaller", "redirect deploy failed", e);
            return;
        }

        // ➌  Make sure the new libs are world-readable / executable
        chmod(new File(variantRoot, "usr/lib/libredirect.so"));
        chmod(new File(variantRoot, "usr/lib/libredirect-bionic.so"));

        final String EXTRAS_TAR = "extras.tzst";          // ➊  add this to assets/
        // ➋  Unpack straight into imagefs, preserving relative paths.
        try (InputStream in  = ctx.getAssets().open(EXTRAS_TAR)) {
            TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD,      // you said .tzst
                    in, variantRoot);                  // helper already exists in the project
        } catch (IOException e) {
            Log.e("ImageFsInstaller", "extras deploy failed", e);
            return;
        }

        // ➌  Make sure the new libs are world-readable / executable
        chmod(new File(variantRoot, "generate_interfaces_file.exe"));
        chmod(new File(variantRoot, "Steamless/Steamless.CLI.exe"));
        chmod(new File(variantRoot, "opt/mono-gecko-offline/wine-mono-9.0.0-x86.msi"));
    }

    private static void chmod(File f) { if (f.exists()) FileUtils.chmod(f, 0755);}

    public static Future<Boolean> installIfNeededFuture(final Context context, AssetManager assetManager) {
        return installIfNeededFuture(context, assetManager, null, null);
    }
    public static Future<Boolean> installIfNeededFuture(final Context context, AssetManager assetManager, Container container, Callback<Integer> onProgress) {
        String variant = container.getContainerVariant();
        if (!isVariantImageFsValid(context, variant)) {
            Log.d("ImageFsInstaller", "Installing image from assets for variant " + variant);
            return installFromAssetsFuture(context, assetManager, variant, onProgress);
        }
        Log.d("ImageFsInstaller", "Image FS already valid for variant " + variant);
        return Executors.newSingleThreadExecutor().submit(() -> {
            ImageFs.ensureImageFsSymlink(context, variant);

            // TODO: Apparently this is needed. Check why later.
            resetContainerImgVersions(context);
            return true;
        });
    }

    public static void generateCompactContainerPattern(final Context context, AssetManager assetManager) {
        // AppUtils.keepScreenOn(context);
        // PreloaderDialog preloaderDialog = new PreloaderDialog(context);
        // preloaderDialog.show(R.string.loading);
        Executors.newSingleThreadExecutor().execute(() -> {
            File[] srcFiles, dstFiles;
            File rootDir = ImageFs.find(context).getRootDir();
            File wineSystem32Dir = new File(rootDir, "/opt/wine/lib/wine/x86_64-windows");
            File wineSysWoW64Dir = new File(rootDir, "/opt/wine/lib/wine/i386-windows");

            File containerPatternDir = new File(context.getCacheDir(), "container_pattern_gamenative");
            FileUtils.delete(containerPatternDir);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, assetManager, "container_pattern_gamenative.tzst", containerPatternDir);

            File containerSystem32Dir = new File(containerPatternDir, ".wine/drive_c/windows/system32");
            File containerSysWoW64Dir = new File(containerPatternDir, ".wine/drive_c/windows/syswow64");

            dstFiles = containerSystem32Dir.listFiles();
            srcFiles = wineSystem32Dir.listFiles();

            ArrayList<String> system32Files = new ArrayList<>();
            ArrayList<String> syswow64Files = new ArrayList<>();

            for (File dstFile : dstFiles) {
                for (File srcFile : srcFiles) {
                    if (dstFile.getName().equals(srcFile.getName())) {
                        if (FileUtils.contentEquals(srcFile, dstFile)) system32Files.add(srcFile.getName());
                        break;
                    }
                }
            }

            dstFiles = containerSysWoW64Dir.listFiles();
            srcFiles = wineSysWoW64Dir.listFiles();

            for (File dstFile : dstFiles) {
                for (File srcFile : srcFiles) {
                    if (dstFile.getName().equals(srcFile.getName())) {
                        if (FileUtils.contentEquals(srcFile, dstFile)) syswow64Files.add(srcFile.getName());
                        break;
                    }
                }
            }

            try {
                JSONObject data = new JSONObject();

                JSONArray system32JSONArray = new JSONArray();
                for (String name : system32Files) {
                    FileUtils.delete(new File(containerSystem32Dir, name));
                    system32JSONArray.put(name);
                }
                data.put("system32", system32JSONArray);

                JSONArray syswow64JSONArray = new JSONArray();
                for (String name : syswow64Files) {
                    FileUtils.delete(new File(containerSysWoW64Dir, name));
                    syswow64JSONArray.put(name);
                }
                data.put("syswow64", syswow64JSONArray);

                FileUtils.writeString(new File(context.getCacheDir(), "common_dlls.json"), data.toString());

                File outputFile = new File(context.getCacheDir(), "container_pattern_gamenative.tzst");
                FileUtils.delete(outputFile);
                TarCompressorUtils.compress(TarCompressorUtils.Type.ZSTD, new File(containerPatternDir, ".wine"), outputFile, 22);

                FileUtils.delete(containerPatternDir);
                // preloaderDialog.closeOnUiThread();
            }
            catch (JSONException e) {
                Log.e("ImageFsInstaller", "Failed to read JSON data: " + e);
            }
        });
    }

    /**
     * Clears Steam DLL markers for all containers by scanning each mapped drive path.
     * Relies only on container drive mappings; does not call into SteamService.
     */
    private static void clearSteamDllMarkers(Context context, ContainerManager containerManager) {
        try {
            for (Container container : containerManager.getContainers()) {
                try {
                    int gameId = ContainerUtils.INSTANCE.extractGameIdFromContainerId(container.id);
                    String mappedPath = SteamService.Companion.getAppDirPath(gameId);
                    MarkerUtils.INSTANCE.removeMarker(mappedPath, Marker.STEAM_DLL_REPLACED);
                    MarkerUtils.INSTANCE.removeMarker(mappedPath, Marker.STEAM_DLL_RESTORED);
                    MarkerUtils.INSTANCE.removeMarker(mappedPath, Marker.STEAM_COLDCLIENT_USED);
                    Log.i("ImageFsInstaller", "Cleared markers for container: " + container.getName() + " (ID: " + container.id + ")");
                } catch (Exception e) {
                    Log.w("ImageFsInstaller", "Failed to clear markers for container ID " + container.id + ": " + e.getMessage());
                }
            }
            Log.i("ImageFsInstaller", "Finished clearing Steam DLL markers for all containers");
        } catch (Exception e) {
            Log.e("ImageFsInstaller", "Error clearing Steam DLL markers: " + e.getMessage());
        }
    }

    /** True if the given variant's imagefs is installed and at latest version. */
    public static boolean isVariantImageFsValid(Context context, String variant) {
        // Legacy: if files/imagefs is a real directory (pre-variant layout), remove it so we do a fresh install.
        File imagefs = new File(context.getFilesDir(), "imagefs");
        if (imagefs.exists() && imagefs.isDirectory() && !FileUtils.isSymlink(imagefs)) {
            FileUtils.delete(imagefs);
            return false;
        }
        File root = ImageFs.getVariantRootDir(context, variant);
        if (!root.isDirectory()) return false;
        File versionFile = new File(root, ".winlator/.img_version");
        if (!versionFile.exists()) return false;
        try {
            List<String> lines = FileUtils.readLines(versionFile);
            if (lines == null || lines.isEmpty()) return false;
            int version = Integer.parseInt(lines.get(0).trim());
            return version >= LATEST_VERSION;
        } catch (Exception e) {
            return false;
        }
    }

    private static File getImageFsSharedDir(Context context) {
        return new File(context.getFilesDir(), "imagefs_shared");
    }

    /**
     * For Bionic: ensures rootDir/opt/proton is a symlink to imagefs_shared/proton.
     * Path resolution then uses /opt/proton/<current version> (see WineInfo).
     */
    private static void ensureSharedProtonDir(Context context, File rootDir) {
        File optProton = new File(rootDir, "opt/proton");
        File sharedProton = ImageFs.getSharedProtonDir(context);
        if (optProton.exists()) return;
        try {
            FileUtils.symlink(sharedProton.getAbsolutePath(), optProton.getAbsolutePath());
            Log.d("ImageFsInstaller", "Created opt/proton -> imagefs_shared/proton for Bionic");
        } catch (Exception e) {
            Log.e("ImageFsInstaller", "ensureSharedProtonDir failed", e);
        }
    }

    /**
     * Ensures that:
     * - A shared home backing directory exists at imagefs_shared/home (containing xuser, etc.)
     * - The given imagefs rootDir exposes /home as a symlink to that shared root.
     *
     * This allows the same user home (e.g. .wine, .cache) to be shared across variants.
     */
    private static void ensureSharedHomeRoot(Context context, File rootDir) {
        File sharedHomeRoot = new File(getImageFsSharedDir(context), "home");
        if (!sharedHomeRoot.exists()) {
            sharedHomeRoot.mkdirs();
        }

        File homePathInImageFs = new File(rootDir, "home");
        if (homePathInImageFs.exists() && !FileUtils.isSymlink(homePathInImageFs)) {
            // Migrate existing /home contents into the shared backing directory
            File[] children = homePathInImageFs.listFiles();
            if (children != null) {
                for (File child : children) {
                    File target = new File(sharedHomeRoot, child.getName());
                    if (!target.exists()) {
                        child.renameTo(target);
                    }
                }
            }
            FileUtils.delete(homePathInImageFs);
        }
        if (!homePathInImageFs.exists()) {
            FileUtils.symlink(sharedHomeRoot.getPath(), homePathInImageFs.getPath());
        }
    }
}
