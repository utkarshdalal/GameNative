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

    private File adrenotoolsContentDir;
    private Context mContext;

    public AdrenotoolsManager(Context context) {
        this.mContext = context;
        this.adrenotoolsContentDir = new File(mContext.getFilesDir(), "contents/adrenotools");
        if (!adrenotoolsContentDir.exists())
            adrenotoolsContentDir.mkdirs();
    }

    public String getLibraryName(String adrenoToolsDriverId) {
        String libraryName = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            libraryName = jsonObject.getString("libraryName");
        }
        catch (JSONException e) {
        }
        return libraryName;
    }

    public String getDriverName(String adrenoToolsDriverId) {
        String driverName = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            driverName = jsonObject.getString("name");
        }
        catch (JSONException e) {
        }
        return driverName;
    }

    public String getDriverVersion(String adrenoToolsDriverId) {
        String driverVersion = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            driverVersion = jsonObject.getString("driverVersion");
        }
        catch (JSONException e) {
        }
        return driverVersion;
    }

    private void reloadContainers(String adrenoToolsDriverId) {
        ContainerManager containerManager = new ContainerManager(mContext);
        for (Container container : containerManager.getContainers()) {
            KeyValueSet config = new KeyValueSet(container.getGraphicsDriverConfig());
            Log.d("AdrenotoolsManager", "Checking if container driver version " + config.get("version") + " matches " + getDriverName(adrenoToolsDriverId));
            if (config.get("version").contains(getDriverName(adrenoToolsDriverId))) {
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

        for (File f : adrenotoolsContentDir.listFiles()) {
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
        if (tmpDir.exists()) tmpDir.delete();
        tmpDir.mkdirs();
        ZipInputStream zis;
        InputStream is;
        String name = "";

        try {
            is = mContext.getContentResolver().openInputStream(driverUri);
            zis = new ZipInputStream(is);
            ZipEntry entry = zis.getNextEntry();
            while (entry != null) {
                File dstFile = new File(tmpDir, entry.getName());
                Files.copy(zis, dstFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
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
                Log.d("AdrenotoolsManager", "Failed to install driver, a valid driver has not been selected");
                tmpDir.delete();
            }
        }
        catch (IOException e) {
            Log.d("AdrenotoolsManager", "Failed to install driver, a valid driver has not been selected");
            tmpDir.delete();
        }

        return name;
    }

    public void setDriverById(EnvVars envVars, ImageFs imagefs, String adrenotoolsDriverId) {
        if (extractDriverFromResources(adrenotoolsDriverId) || enumarateInstalledDrivers().contains(adrenotoolsDriverId)) {
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
