package com.winlator.core;

import app.gamenative.BuildConfig;

/**
 * Paths inside this app's own data directory, derived from the running applicationId.
 *
 * <p>Several defaults used to hardcode {@code /data/data/app.gamenative/...}. That only works for the
 * standard build: any build with a different applicationId (the {@code .gold} variant, forks) has its
 * own sandbox and cannot write into another app's data directory. Use these constants instead, and
 * pass stored strings that may contain old paths (container drives and env vars, community configs)
 * through {@link #rewrite(String)}.
 *
 * <p>When the applicationId is {@code app.gamenative}, every value here is identical to the old
 * hardcoded paths and {@link #rewrite(String)} returns its input unchanged.
 */
public final class AppPaths {
    /** applicationId of the standard GameNative build, which older stored paths refer to. */
    public static final String UPSTREAM_PACKAGE = "app.gamenative";

    /** applicationId of this build. */
    public static final String PACKAGE = BuildConfig.APPLICATION_ID;

    public static final String DATA_DIR = "/data/data/" + PACKAGE;
    public static final String FILES_DIR = DATA_DIR + "/files";
    public static final String IMAGEFS_DIR = FILES_DIR + "/imagefs";
    public static final String STORAGE_DIR = DATA_DIR + "/storage";

    private static final String[] PATH_PREFIXES = {"/data/data/", "/data/user/0/", "/Android/data/"};

    private AppPaths() {}

    /**
     * Points paths that refer to the standard build's data directories at this build's instead.
     * Handles the {@code /data/data/}, {@code /data/user/0/} and external {@code /Android/data/}
     * spellings. Safe to call repeatedly; returns the input unchanged for the standard build.
     */
    public static String rewrite(String value) {
        if (value == null || value.isEmpty() || UPSTREAM_PACKAGE.equals(PACKAGE)) return value;
        String result = value;
        for (String prefix : PATH_PREFIXES) {
            // The trailing "/" keeps this from matching paths that already use a longer package name.
            result = result.replace(prefix + UPSTREAM_PACKAGE + "/", prefix + PACKAGE + "/");
        }
        return result;
    }
}
