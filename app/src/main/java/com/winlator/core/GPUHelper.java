package com.winlator.core;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.collection.ArrayMap;
import android.util.Log;
import dalvik.annotation.optimization.CriticalNative;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL10;

public abstract class GPUHelper {
    public static int VK_API_VERSION_1_3 = vkMakeVersion(1, 3, 0);

    @CriticalNative
    public static native int vkGetApiVersion();

    public static native String[] vkGetDeviceExtensions();

    static {
        System.loadLibrary("winlator_11");
    }

    public static int vkVersionPatch(){
        try {
            return vkGetApiVersion() & 0xFFF;
        } catch (UnsatisfiedLinkError e) {
            Log.e("GPUHelper", "Failed to load Vulkan library", e);
            return 0; // Fallback if library not loaded
        } catch (Exception e) {
            Log.e("GPUHelper", "Failed to get Vulkan version patch", e);
            return 0; // Fallback for any other error
        }
    }

    public static int vkMakeVersion(String value) {
        Pattern pattern = Pattern.compile("([0-9]+)\\.([0-9]+)\\.?([0-9]+)?");
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) {
            return 0;
        }
        try {
            int major = matcher.group(1) != null ? Integer.parseInt(matcher.group(1)) : 0;
            int minor = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 0;
            int patch = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0;
            if (matcher.group(1) == null && patch == 0) {
                patch = minor;
            }
            return vkMakeVersion(major, minor, patch);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static int vkMakeVersion(int major, int minor, int patch) {
        return (major << 22) | (minor << 12) | patch;
    }

    public static int vkVersionMajor(int version) {
        return version >> 22;
    }

    public static int vkVersionMinor(int version) {
        return (version >> 12) & 1023;
    }
}
