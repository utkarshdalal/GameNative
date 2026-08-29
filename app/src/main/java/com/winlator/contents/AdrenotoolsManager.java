package com.winlator.contents;

import android.content.res.AssetManager;
import android.net.Uri;

import android.content.Context;
import android.util.Log;
import com.winlator.container.Container;
import com.winlator.container.Shortcut;
import com.winlator.container.ContainerManager;
import com.winlator.core.DefaultVersion;
import com.winlator.core.KeyValueSet;
import com.winlator.core.FileUtils;
import com.winlator.core.GPUInformation;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.envvars.EnvVars;
import com.winlator.xenvironment.ImageFs;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONException;
import org.json.JSONObject;

public class AdrenotoolsManager {

    private static final String PACKAGE_TYPE_ICD = "icd";
    private static final String PACKAGE_TYPE_VULKAN_LAYER = "vulkanLayer";

    private File adrenotoolsContentDir;
    private Context mContext;

    public AdrenotoolsManager(Context context) {
        this.mContext = context;
        this.adrenotoolsContentDir = new File(mContext.getFilesDir(), "contents/adrenotools");
        if (!adrenotoolsContentDir.exists())
            adrenotoolsContentDir.mkdirs();
    }

    private JSONObject getMetadata(String adrenoToolsDriverId) {
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            if (!metaProfile.isFile()) return null;
            return new JSONObject(FileUtils.readString(metaProfile));
        }
        catch (JSONException e) {
            Log.w("AdrenotoolsManager", "Invalid driver metadata for " + adrenoToolsDriverId, e);
            return null;
        }
    }

    public String getLibraryName(String adrenoToolsDriverId) {
        JSONObject jsonObject = getMetadata(adrenoToolsDriverId);
        return jsonObject != null ? jsonObject.optString("libraryName", "") : "";
    }

    public String getDriverName(String adrenoToolsDriverId) {
        JSONObject jsonObject = getMetadata(adrenoToolsDriverId);
        return jsonObject != null ? jsonObject.optString("name", "") : "";
    }

    public String getDriverVersion(String adrenoToolsDriverId) {
        JSONObject jsonObject = getMetadata(adrenoToolsDriverId);
        return jsonObject != null ? jsonObject.optString("driverVersion", "") : "";
    }

    /**
     * GameNative traditionally imports AdrenoTools ICD packages.  ExynosTools is
     * deliberately not an ICD: it is a Vulkan layer that must run on top of the
     * Samsung system ICD.  packageType lets the same picker install both without
     * attempting to pass a layer library to adrenotools_open_libvulkan().
     */
    public String getPackageType(String adrenoToolsDriverId) {
        JSONObject jsonObject = getMetadata(adrenoToolsDriverId);
        return jsonObject != null ? jsonObject.optString("packageType", PACKAGE_TYPE_ICD) : PACKAGE_TYPE_ICD;
    }

    private String getManifestName(String adrenoToolsDriverId) {
        JSONObject jsonObject = getMetadata(adrenoToolsDriverId);
        return jsonObject != null ? jsonObject.optString("manifestName", "") : "";
    }

    private void reloadContainers(String adrenotoolsDriverId) {
        ContainerManager containerManager = new ContainerManager(mContext);
        for (Container container : containerManager.getContainers()) {
            KeyValueSet config = new KeyValueSet(container.getGraphicsDriverConfig());
            Log.d("AdrenotoolsManager", "Checking if container driver version " + config.get("version") + " matches " + getDriverName(adrenotoolsDriverId));
            if (config.get("version").contains(getDriverName(adrenotoolsDriverId))) {
                Log.d("AdrenotoolsManager", "Found a match for container " + container.getName());
                config.put("version", DefaultVersion.WRAPPER);
                container.setGraphicsDriverConfig(config.toString());
                container.saveData();
            }
        }
    }

    public void removeDriver(String adrenoToolsDriverId) {
        Log.d("AdrenotoolsManager", "Removing driver " + adrenoToolsDriverId);
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        reloadContainers(adrenoToolsDriverId);
        FileUtils.delete(driverPath);
    }

    public ArrayList<String> enumarateInstalledDrivers() {
        ArrayList<String> driversList = new ArrayList<>();

        File[] files = adrenotoolsContentDir.listFiles();
        if (files == null) return driversList;
        for (File f : files) {
            boolean fromResources = isFromResources("graphics_driver/adrenotools-" + f.getName() + ".tzst");
            if (!fromResources && new File(f, "meta.json").exists())
                driversList.add(f.getName());
        }
        return driversList;
    }

    private boolean isFromResources(String driver) {
        AssetManager am = mContext.getResources().getAssets();
        InputStream is = null;
        boolean isFromResources = true;

        try {
            is = am.open(driver);
            is.close();
        }
        catch (IOException e) {
            isFromResources = false;
        }

        return isFromResources;
    }

    private boolean extractDriverFromResources(String adrenotoolsDriverId) {
        String src = "graphics_driver/adrenotools-" + adrenotoolsDriverId + ".tzst";
        boolean hasExtracted;

        File dst = new File(adrenotoolsContentDir, adrenotoolsDriverId);
        if (dst.exists())
            dst.delete();

        dst.mkdirs();
        Log.d("AdrenotoolsManager", "Extracting " + src + " to " + dst.getAbsolutePath());
        hasExtracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, mContext, src, dst);

        if (!hasExtracted)
            dst.delete();

        return hasExtracted;
    }

    public String installDriver(Uri driverUri) {
        File tmpDir = new File(adrenotoolsContentDir, "tmp");
        if (tmpDir.exists()) FileUtils.delete(tmpDir);
        tmpDir.mkdirs();
        ZipInputStream zis;
        InputStream is;
        String name = "";

        try {
            is = mContext.getContentResolver().openInputStream(driverUri);
            if (is == null) throw new IOException("Unable to open selected package");
            zis = new ZipInputStream(is);
            ZipEntry entry = zis.getNextEntry();
            while (entry != null) {
                if (!entry.isDirectory()) {
                    File dstFile = new File(tmpDir, entry.getName());
                    String rootPath = tmpDir.getCanonicalPath() + File.separator;
                    String dstPath = dstFile.getCanonicalPath();
                    if (!dstPath.startsWith(rootPath))
                        throw new IOException("Unsafe ZIP entry: " + entry.getName());
                    File parent = dstFile.getParentFile();
                    if (parent != null) parent.mkdirs();
                    Files.copy(zis, dstFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                entry = zis.getNextEntry();
            }
            zis.close();
            if (new File(tmpDir, "meta.json").exists()) {
                name = getDriverName(tmpDir.getName());
                File dst = new File(adrenotoolsContentDir, name);
                if (!dst.exists() && !name.equals(""))
                    tmpDir.renameTo(dst);
                else {
                    name = "";
                    FileUtils.delete(tmpDir);
                }
            }
            else {
                Log.d("AdrenotoolsManager", "Failed to install driver, root-level meta.json is missing");
                FileUtils.delete(tmpDir);
            }
        }
        catch (IOException e) {
            Log.e("AdrenotoolsManager", "Failed to install selected driver/layer package", e);
            FileUtils.delete(tmpDir);
        }

        return name;
    }

    private boolean configureVulkanLayerPackage(EnvVars envVars, ImageFs imagefs, String packageId) {
        String driverPath = adrenotoolsContentDir.getAbsolutePath() + "/" + packageId + "/";
        String libraryName = getLibraryName(packageId);
        String manifestName = getManifestName(packageId);
        File library = new File(driverPath, libraryName);
        File manifest = new File(driverPath, manifestName);

        if (libraryName.isEmpty() || manifestName.isEmpty() || !library.isFile() || !manifest.isFile()) {
            Log.e("AdrenotoolsManager", "Vulkan layer package is incomplete: " + packageId);
            return false;
        }

        // Preserve GameNative's standard Vulkan layer directories while putting
        // the imported layer first. LsfgVkManager subsequently appends its own
        // per-container implicit_layer.d path when LSFG is armed.
        String root = imagefs.getRootDir().getPath();
        String layerPath = driverPath
                + ":" + root + "/usr/share/vulkan/implicit_layer.d"
                + ":" + root + "/usr/share/vulkan/explicit_layer.d";
        envVars.put("VK_LAYER_PATH", layerPath);
        envVars.put("EXYNOSTOOLS_LAYER_PATH", driverPath);
        envVars.remove("DISABLE_VORTEK_XCLIPSE_LAYER");

        // Explicitly remove custom-ICD variables so the Samsung system ICD is
        // used. ExynosTools is a layer over that ICD, not a replacement for it.
        envVars.remove("ADRENOTOOLS_DRIVER_PATH");
        envVars.remove("ADRENOTOOLS_DRIVER_NAME");
        envVars.remove("ADRENOTOOLS_HOOKS_PATH");

        Log.i("AdrenotoolsManager", "Configured Vulkan layer package " + packageId
                + " over the system ICD: " + driverPath);
        return true;
    }

    public void setDriverById(EnvVars envVars, ImageFs imagefs, String adrenotoolsDriverId) {
        if (extractDriverFromResources(adrenotoolsDriverId) || enumarateInstalledDrivers().contains(adrenotoolsDriverId)) {
            if (PACKAGE_TYPE_VULKAN_LAYER.equalsIgnoreCase(getPackageType(adrenotoolsDriverId))) {
                configureVulkanLayerPackage(envVars, imagefs, adrenotoolsDriverId);
                return;
            }

            String driverPath = adrenotoolsContentDir.getAbsolutePath() + "/" + adrenotoolsDriverId + "/";
            if (!getLibraryName(adrenotoolsDriverId).equals("")) {
                envVars.put("ADRENOTOOLS_DRIVER_PATH", driverPath);
                envVars.put("ADRENOTOOLS_HOOKS_PATH", imagefs.getLibDir());
                envVars.put("ADRENOTOOLS_DRIVER_NAME", getLibraryName(adrenotoolsDriverId));
                if (adrenotoolsDriverId.contains("v762") && GPUInformation.getVersion(mContext).contains("512.530")) {
                    Log.d("AdrenotoolsManager", "Patching v762 driver for stock v530");
                    FileUtils.writeToBinaryFile(driverPath + "notadreno_utils.so", 0x2680, 3);
                } else if (adrenotoolsDriverId.contains("v762") && GPUInformation.getVersion(mContext).contains("512.502")) {
                    Log.d("AdrenotoolsManager", "Patching v762 driver for stock v502");
                    FileUtils.writeToBinaryFile(driverPath + "notadreno_utils.so", 0x2680, 2);
                }
            }
        } else if (adrenotoolsDriverId != null && !adrenotoolsDriverId.isEmpty()
                && !adrenotoolsDriverId.equalsIgnoreCase("System")) {
            Log.w("AdrenotoolsManager", "Driver not found: " + adrenotoolsDriverId
                + " - Falling back to System driver");
        }
    }
}
