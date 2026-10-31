package com.winlator.core;

import android.content.Context;

import com.winlator.core.envvars.EnvVars;
import com.winlator.xenvironment.ImageFs;

import java.io.File;

public class DXVKHelper {
    public static final String DEFAULT_CONFIG = "version="+DefaultVersion.DXVK+",framerate=0,maxDeviceMemory=0";

    public static KeyValueSet parseConfig(Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() : DEFAULT_CONFIG;
        return new KeyValueSet(data);
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        ImageFs imageFs = ImageFs.find(context);
        envVars.put("DXVK_STATE_CACHE_PATH", "/data/data/app.gamenative/files/imagefs"+ImageFs.CACHE_PATH);
        envVars.put("DXVK_LOG_LEVEL", "none");

        File rootDir = ImageFs.find(context).getRootDir();
        File dxvkConfigFile = new File(imageFs.config_path+"/dxvk.conf");

        // DXVK_CONFIG is split on ';' by DXVK and read raw from the environment (execve, no shell),
        // so entries must be ';'-separated with no surrounding quotes. The old "\"...\n...\"" form
        // made DXVK see a key starting with '"' and silently drop every option.
        StringBuilder content = new StringBuilder();
        // Keep any DXVK_CONFIG the user set in the container env vars instead of overwriting it.
        String userConfig = envVars.get("DXVK_CONFIG").trim();
        if (userConfig.length() >= 2 && userConfig.startsWith("\"") && userConfig.endsWith("\""))
            userConfig = userConfig.substring(1, userConfig.length() - 1);
        for (String option : userConfig.split("[;\n]")) {
            if (!option.trim().isEmpty()) appendOption(content, option.trim());
        }
        String maxDeviceMemory = config.get("maxDeviceMemory");
        if (!maxDeviceMemory.isEmpty() && !maxDeviceMemory.equals("0")) {
            appendOption(content, "dxgi.maxDeviceMemory = " + maxDeviceMemory);
            appendOption(content, "dxgi.maxSharedMemory = " + maxDeviceMemory);
            // dxgi.* only covers D3D10/11; D3D9 titles read their reported VRAM from this one.
            appendOption(content, "d3d9.maxAvailableMemory = " + maxDeviceMemory);
        }

        String maxFeatureLevel = config.get("maxFeatureLevel");
        if (!maxFeatureLevel.isEmpty() && !maxFeatureLevel.equals("0")) {
            appendOption(content, "d3d11.maxFeatureLevel = " + maxFeatureLevel);
            envVars.put("DXVK_FEATURE_LEVEL", maxFeatureLevel);
        }

        String framerate = config.get("framerate");
        if (!framerate.isEmpty() && !framerate.equals("0")) {
            envVars.put("DXVK_FRAME_RATE", framerate);
        }
        String customDevice = config.get("customDevice");
        String[] parts = customDevice.split(":", 3);
        if (parts.length == 3) {
            String desc = parts[2].replace("\"", "").replace(";", "");
            appendOption(content, "dxgi.customDeviceId = " + parts[0]);
            appendOption(content, "dxgi.customVendorId = " + parts[1]);
            appendOption(content, "d3d9.customDeviceId = " + parts[0]);
            appendOption(content, "d3d9.customVendorId = " + parts[1]);
            appendOption(content, "dxgi.customDeviceDesc = \"" + desc + "\"");
            appendOption(content, "d3d9.customDeviceDesc = \"" + desc + "\"");
        }
        if (config.getBoolean("constantBufferRangeCheck")) {
            appendOption(content, "d3d11.constantBufferRangeCheck = True");
        }

        String async = config.get("async");
        if (!async.isEmpty() && !async.equals("0"))
            envVars.put("DXVK_ASYNC", "1");

        String asyncCache = config.get("asyncCache");
        if (!asyncCache.isEmpty() && !asyncCache.equals("0"))
            envVars.put("DXVK_GPLASYNCCACHE", "1");


        envVars.put("DXVK_CONFIG_FILE", rootDir + ImageFs.CONFIG_PATH+"/dxvk.conf");
        if (content.length() > 0) envVars.put("DXVK_CONFIG", content.toString());
        else envVars.remove("DXVK_CONFIG");
    }

    private static void appendOption(StringBuilder content, String option) {
        if (content.length() > 0) content.append("; ");
        content.append(option);
    }

    public static void setVKD3DEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        String featureLevel = config.get("vkd3dFeatureLevel", "12_1");
        envVars.put("VKD3D_FEATURE_LEVEL", featureLevel);
    }
}
