package com.winlator.core;

import android.content.Context;
import android.opengl.EGL14;
import android.util.Log;

import androidx.collection.ArrayMap;

import com.winlator.PrefManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Objects;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

public abstract class GPUInformation {

    static {
        System.loadLibrary("extras");
    }

    public static String getDeviceIdFromGPUName(Context context, String gpuName) {
        String gpuNameList = FileUtils.readString(context, "gpu_cards.json");
        String deviceId = "";
        try {
            JSONArray jsonArray = new JSONArray(gpuNameList);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jobj = jsonArray.getJSONObject(i);
                if (jobj.getString("name").contains(gpuName)) {
                    deviceId = jobj.getString("deviceID");
                }
            }
        }
        catch (JSONException e) {
        }
        return deviceId;
    }

    public static String getVendorIdFromGPUName(Context context, String gpuName) {
        String gpuNameList = FileUtils.readString(context, "gpu_cards.json");
        String vendorId = "";
        try {
            JSONArray jsonArray = new JSONArray(gpuNameList);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jobj = jsonArray.getJSONObject(i);
                if (jobj.getString("name").contains(gpuName)) {
                    vendorId = jobj.getString("vendorID");
                }
            }
        }
        catch (JSONException e) {
        }
        return vendorId;
    }

    private static ArrayMap<String, String> loadGPUInformation(Context context) {
        final Thread thread = Thread.currentThread();
        final ArrayMap<String, String> gpuInfo = new ArrayMap<>();
        gpuInfo.put("renderer", "");
        gpuInfo.put("vendor", "");
        gpuInfo.put("version", "");

        (new Thread(() -> {
            int[] attribList = new int[]{
                    EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                    EGL10.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 0,
                    EGL10.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] configCounts = new int[1];

            EGL10 egl = (EGL10) EGLContext.getEGL();
            EGLDisplay eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);

            int[] version = new int[2];
            egl.eglInitialize(eglDisplay, version);
            egl.eglChooseConfig(eglDisplay, attribList, configs, 1, configCounts);

            attribList = new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE};
            EGLContext eglContext = egl.eglCreateContext(eglDisplay, configs[0], EGL10.EGL_NO_CONTEXT, attribList);

            egl.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, eglContext);

            GL10 gl = (GL10) eglContext.getGL();
            String gpuRenderer = Objects.toString(gl.glGetString(GL10.GL_RENDERER), "");
            String gpuVendor = Objects.toString(gl.glGetString(GL10.GL_VENDOR), "");
            String gpuVersion = Objects.toString(gl.glGetString(GL10.GL_VERSION), "");

            gpuInfo.put("renderer", gpuRenderer);
            gpuInfo.put("vendor", gpuVendor);
            gpuInfo.put("version", gpuVersion);

            PrefManager.init(context);
            PrefManager.putString("gpu_renderer", gpuRenderer);
            PrefManager.putString("gpu_vendor", gpuVendor);
            PrefManager.putString("gpu_version", gpuVersion);

            synchronized (thread) {
                thread.notify();
            }
        })).start();

        synchronized (thread) {
            try {
                thread.wait();
            } catch (InterruptedException e) {
                Log.e("GPUInformation", "Failed to load gpu information: " + e);
            }
        }
        return gpuInfo;
    }

    public static String getRenderer(Context context) {
        PrefManager.init(context);
        String value = PrefManager.getString("gpu_renderer", "");
        if (!value.isEmpty()) return value;

        ArrayMap<String, String> gpuInfo = loadGPUInformation(context);
        return gpuInfo.get("renderer");
    }

    public static String getVendor(Context context) {
        PrefManager.init(context);
        String value = PrefManager.getString("gpu_vendor", "");
        if (!value.isEmpty()) return value;

        ArrayMap<String, String> gpuInfo = loadGPUInformation(context);
        return gpuInfo.get("vendor");
    }

    public static String getVersion(Context context) {
        PrefManager.init(context);
        String value = PrefManager.getString("gpu_version", "");
        if (!value.isEmpty()) return value;

        ArrayMap<String, String> gpuInfo = loadGPUInformation(context);
        return gpuInfo.get("version");
    }

    public static boolean isAdreno6xx(Context context) {
        return getRenderer(context).toLowerCase(Locale.ENGLISH).matches(".*adreno[^6]+6[0-9]{2}.*");
    }

    public static boolean isAdreno8Elite(Context context) {
        String r = getRenderer(context).toLowerCase(Locale.ENGLISH);
        // “adreno … 83x / 84x / 85x”
        return r.contains("adreno") && r.matches(".*\\b8(3[0-9]|4[0-9]|5[0-9])\\b.*");
    }

    public static boolean isTurnipCapable(Context context) {
        String r = getRenderer(context).toLowerCase(Locale.ENGLISH);
        // match “adreno 610…699” or “adreno 710…799”
        return r.contains("adreno") && r.matches(".*\\b[67][0-9]{2}\\b.*");
    }

    /**
     * Detects Adreno 710-, 720-, or 732-class GPUs.
     *
     * @return true if the renderer string contains “adreno” and the exact model
     *         number 710, or 720; false otherwise.
     */
    public static boolean isAdreno710_720_732(Context context) {
        String r = getRenderer(context).toLowerCase(Locale.ENGLISH);
        return r.contains("adreno") && r.matches(".*\\b(710|720|732)\\b.*");
    }


    public static boolean isAdrenoGPU(Context context) {
        return getRenderer(null, context).toLowerCase().contains("adreno");
    }

    public static boolean isDriverSupported(String driverName, Context context) {
        if (!isAdrenoGPU(context) && !driverName.equals("System"))
            return false;

        String renderer = getRenderer(driverName, context);

        return !renderer.toLowerCase().contains("unknown");
    }

    // This method appears to crash on several devices
    public native static String getVulkanVersion(String driverName, Context context);
    public native static String getRenderer(String driverName, Context context);
    public native static String[] enumerateExtensions(String driverName, Context context);
    public native static int getVendorID(String driverName, Context context);
}
