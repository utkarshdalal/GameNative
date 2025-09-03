package com.winlator.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class GPUHelper {
    public static native String[] vkGetDeviceExtensions();

    static {
        System.loadLibrary("winlator_10");
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
