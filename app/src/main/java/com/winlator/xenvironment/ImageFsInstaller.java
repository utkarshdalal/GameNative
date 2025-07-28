package com.winlator.xenvironment;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

// import com.winlator.MainActivity;
// import com.winlator.R;
// import com.winlator.SettingsFragment;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
// import com.winlator.core.DownloadProgressDialog;
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
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public abstract class ImageFsInstaller {
    public static final byte LATEST_VERSION = 21;

    private static void resetContainerImgVersions(Context context) {
        ContainerManager manager = new ContainerManager(context);
        for (Container container : manager.getContainers()) {
            String imgVersion = container.getExtra("imgVersion");
            String wineVersion = container.getWineVersion();
            if (!imgVersion.isEmpty() && WineInfo.isMainWineVersion(wineVersion) && Short.parseShort(imgVersion) <= 5) {
                container.putExtra("wineprefixNeedsUpdate", "t");
            }

            container.putExtra("imgVersion", null);
            container.saveData();
        }
    }

    private static Future<Boolean> installFromAssetsFuture(final Context context, AssetManager assetManager, Callback<Integer> onProgress) {
        // AppUtils.keepScreenOn(context);
        ImageFs imageFs = ImageFs.find(context);
        final File rootDir = imageFs.getRootDir();

        // SettingsFragment.resetBox86_64Version(context);

        // final DownloadProgressDialog dialog = new DownloadProgressDialog(context);
        // dialog.show(R.string.installing_system_files);
        return Executors.newSingleThreadExecutor().submit(() -> {
            clearRootDir(rootDir);
            final byte compressionRatio = 22;
            final long contentLength = (long)(FileUtils.getSize(assetManager, "imagefs_gamenative.txz") * (100.0f / compressionRatio));
            AtomicLong totalSizeRef = new AtomicLong();

            boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, assetManager, "imagefs_gamenative.txz", rootDir, (file, size) -> {
                if (size > 0) {
                    long totalSize = totalSizeRef.addAndGet(size);
                    if (onProgress != null) {
                        final int progress = (int)(((float)totalSize / contentLength) * 100);
                        onProgress.call(progress);
                    }
                }
                return file;
            });

            if (success) {
                Log.d("ImageFsInstaller", "Successfully installed system files");
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.getAssets(), "box86_64/box64-" + DefaultVersion.BOX64 + ".tzst", rootDir);
                ContainerManager containerManager = new ContainerManager(context);
                File homeDir = new File(rootDir, "home");
                for (Container container : containerManager.getContainers()) {
                    File containerDir = new File(homeDir, ImageFs.USER + "-" + container.id);
                    containerManager.extractContainerPatternFile(container.getWineVersion(), containerDir, null);
                }
                imageFs.createImgVersionFile(LATEST_VERSION);
                resetContainerImgVersions(context);
            }
            else {
                Log.e("ImageFsInstaller", "Failed to install system files");
                // AppUtils.showToast(context, R.string.unable_to_install_system_files);
            }
            return success;
            // dialog.closeOnUiThread();
        });
    }


    public static Future<Boolean> installIfNeededFuture(final Context context, AssetManager assetManager) {
        return installIfNeededFuture(context, assetManager, null);
    }
    public static Future<Boolean> installIfNeededFuture(final Context context, AssetManager assetManager, Callback<Integer> onProgress) {
        ImageFs imageFs = ImageFs.find(context);
        if (!imageFs.isValid() || imageFs.getVersion() < LATEST_VERSION) {
            Log.d("ImageFsInstaller", "Installing image from assets");
            return installFromAssetsFuture(context, assetManager, onProgress);
        } else {
            Log.d("ImageFsInstaller", "Image FS already valid and at latest version");
            return Executors.newSingleThreadExecutor().submit(() -> true);
        }
    }

    private static void clearOptDir(File optDir) {
        File[] files = optDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().equals("installed-wine")) continue;
                FileUtils.delete(file);
            }
        }
    }

    private static void clearRootDir(File rootDir) {
        if (rootDir.isDirectory()) {
            File[] files = rootDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        String name = file.getName();
                        if (name.equals("home")) {
                            continue;
                        }
                    }
                    FileUtils.delete(file);
                }
            }
        }
        else rootDir.mkdirs();
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
}
