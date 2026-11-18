package com.winlator.xenvironment;

import android.content.Context;

import androidx.annotation.NonNull;

import com.winlator.core.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

public class ImageFs {
    private static volatile ImageFs INSTANCE;

    public static final String USER = "xuser";
    public static final String HOME_PATH = "/home/"+USER;
    public static final String CACHE_PATH = HOME_PATH+"/.cache";
    public static final String CONFIG_PATH = HOME_PATH+"/.config";
    public static final String WINEPREFIX = HOME_PATH+"/.wine";
    private final File rootDir;
    public String winePath;
    public String home_path;
    public String cache_path;
    public String config_path;
    public String wineprefix;

    private ImageFs(File rootDir) {
        this.rootDir = rootDir;
        winePath = rootDir + "/opt/wine";
        home_path = rootDir + HOME_PATH;
        cache_path = rootDir + CACHE_PATH;
        config_path = rootDir + CONFIG_PATH;
        wineprefix = rootDir + WINEPREFIX;
    }

    /** Shared Proton directory; opt/<version> in each variant symlinks here. */
    public static File getSharedProtonDir(Context context) {
        File sharedProtonDir = new File(context.getFilesDir(), "imagefs_shared/proton");
        if (!sharedProtonDir.exists()) {
            sharedProtonDir.mkdirs();
        }
        return sharedProtonDir;
    }

    public static ImageFs find(Context context) {
        ImageFs local = INSTANCE;
        if (local != null) return local;
        synchronized (ImageFs.class) {
            if (INSTANCE == null) {
                INSTANCE = new ImageFs(new File(context.getFilesDir(), "imagefs"));
            }
            return INSTANCE;
        }
    }

    public static ImageFs find(File rootDir) {
        return new ImageFs(rootDir);
    }

    public File getRootDir() {
        return rootDir;
    }

    public boolean isValid() {
        return rootDir.isDirectory() && getImgVersionFile().exists();
    }

    /**
     * Returns the image version stored in {@code .img_version}, or {@code 0} if the file is
     * absent, empty, whitespace-only, or contains non-numeric content.
     * @return The parsed version number, or {@code 0} as a safe default.
     */
    public int getVersion() {
        File imgVersionFile = getImgVersionFile();
        if (!imgVersionFile.exists()) return 0;
        String line = FileUtils.readFirstLine(imgVersionFile);
        if (line == null || line.isBlank()) return 0;
        try {
            return Integer.parseInt(line.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public String getFormattedVersion() {
        return String.format(Locale.ENGLISH, "%.1f", (float)getVersion());
    }

    public void createImgVersionFile(int version) {
        getConfigDir().mkdirs();
        File file = getImgVersionFile();
        try {
            file.createNewFile();
            FileUtils.writeString(file, String.valueOf(version));
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Returns the filesystem variant stored in {@code .variant} (e.g. {@code "glibc"}), or
     * {@code ""} if the file is absent, empty, or whitespace-only.
     * @return The variant string, or {@code ""} as a safe default.
     */
    public String getVariant() {
        File variantFile = getVariantFile();
        if (!variantFile.exists()) return "";
        String line = FileUtils.readFirstLine(variantFile);
        return (line == null || line.isBlank()) ? "" : line.trim();
    }

    public void createVariantFile(String variant) {
        getConfigDir().mkdirs();
        File file = getVariantFile();
        try {
            file.createNewFile();
            FileUtils.writeString(file, variant);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getWinePath() {
        return winePath;
    }

    public void setWinePath(String winePath) {
        this.winePath = winePath;
    }

    public File getConfigDir() {
        return new File(rootDir, ".winlator");
    }

    public File getImgVersionFile() {
        return new File(getConfigDir(), ".img_version");
    }

    public File getVariantFile() {
        return new File(getConfigDir(), ".variant");
    }

    public File getInstalledWineDir() {
        return new File(rootDir, "/opt/installed-wine");
    }

    public File getTmpDir() {
        return new File(rootDir, "/tmp");
    }

    public File getLibDir() { return new File(rootDir, "/usr/lib"); }

    public File getBinDir() { return new File(rootDir, "/usr/bin"); }

    public File getShareDir() {
        return new File(rootDir, "/usr/share");
    }

    public File getGlibc32Dir() {
        return new File(rootDir, "/usr/lib/arm-linux-gnueabihf");
    }

    public File getGlibc64Dir() {
        return new File(rootDir, "/usr/lib");
    }

    public File getLib32Dir() {
        return new File(rootDir, "/usr/lib/arm-linux-gnueabihf");
    }

    public File getLib64Dir() {
        return new File(rootDir, "/usr/lib");
    }

    public File getStorageDir() {
        return new File(rootDir, "/storage");
    }

    public File getFilesDir() {
        return rootDir.getParentFile();
    }

    @NonNull
    @Override
    public String toString() {
        return rootDir.getPath();
    }

    public static File getImageFsSharedDir(Context context) {
        File sharedDir = new File(context.getFilesDir(), "imagefs_shared");
        if (!sharedDir.exists()) {
            sharedDir.mkdirs();
        }
        return sharedDir;
    }
}
