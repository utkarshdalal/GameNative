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

    /**
     * Returns (and creates if absent) the shared Proton directory in app storage.
     * @param context Application context used to resolve the files directory.
     * @return The shared Proton directory ({@code imagefs_shared/proton}).
     */
    public static File getSharedProtonDir(Context context) {
        File sharedProtonDir = new File(context.getFilesDir(), "imagefs_shared/proton");
        if (!sharedProtonDir.exists()) {
            sharedProtonDir.mkdirs();
        }
        return sharedProtonDir;
    }

    /**
     * Returns the singleton {@link ImageFs} rooted at {@code context.getFilesDir()/imagefs}.
     * @param context Application context used to resolve the files directory.
     * @return The singleton {@link ImageFs} instance.
     */
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

    /**
     * Returns a new {@link ImageFs} rooted at the given directory; does not use the singleton.
     * @param rootDir The root directory for the image filesystem.
     * @return A new {@link ImageFs} instance.
     */
    public static ImageFs find(File rootDir) {
        return new ImageFs(rootDir);
    }

    /**
     * Returns the root directory of this image filesystem.
     * @return The root {@link File} directory.
     */
    public File getRootDir() {
        return rootDir;
    }

    /**
     * Returns {@code true} if the root directory exists and contains a version file.
     * @return True if this image filesystem appears to be a valid installation.
     */
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

    /**
     * Returns {@link #getVersion()} formatted as a one-decimal-place string (e.g. {@code "26.0"}).
     * @return The version as a formatted string.
     */
    public String getFormattedVersion() {
        return String.format(Locale.ENGLISH, "%.1f", (float)getVersion());
    }

    /**
     * Writes {@code version} to {@code .img_version}, creating the config directory if needed.
     * @param version The version number to persist.
     */
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

    /**
     * Writes {@code variant} to {@code .variant}, creating the config directory if needed.
     * @param variant The variant string to persist (e.g. {@code "glibc"}).
     */
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

    /**
     * Returns the CPU architecture stored in {@code .arch} (e.g. {@code "arm64"}), or {@code ""}
     * if the file is absent, empty, or whitespace-only.
     * @return The architecture string, or {@code ""} as a safe default.
     */
    public String getArch() {
        File archFile = getArchFile();
        if (!archFile.exists()) return "";
        String line = FileUtils.readFirstLine(archFile);
        return (line == null || line.isBlank()) ? "" : line.trim();
    }

    /**
     * Writes {@code arch} to {@code .arch}, creating the config directory if needed.
     * @param arch The architecture string to persist (e.g. {@code "arm64"}).
     */
    public void createArchFile(String arch) {
        getConfigDir().mkdirs();
        File file = getArchFile();
        try {
            file.createNewFile();
            FileUtils.writeString(file, arch);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Returns the path to the Wine installation used by this image.
     * @return The Wine installation path.
     */
    public String getWinePath() {
        return winePath;
    }

    /**
     * Overrides the Wine installation path for this image.
     * @param winePath The new Wine installation path.
     */
    public void setWinePath(String winePath) {
        this.winePath = winePath;
    }

    /**
     * Returns the {@code .winlator} config directory inside the image root.
     * @return The config directory.
     */
    public File getConfigDir() {
        return new File(rootDir, ".winlator");
    }

    /**
     * Returns the {@code .img_version} metadata file.
     * @return The version metadata file.
     */
    public File getImgVersionFile() {
        return new File(getConfigDir(), ".img_version");
    }

    /**
     * Returns the {@code .variant} metadata file.
     * @return The variant metadata file.
     */
    public File getVariantFile() {
        return new File(getConfigDir(), ".variant");
    }

    /**
     * Returns the {@code .arch} metadata file.
     * @return The architecture metadata file.
     */
    public File getArchFile() {
        return new File(getConfigDir(), ".arch");
    }

    /**
     * Returns the directory where the active Wine build is installed ({@code /opt/installed-wine}).
     * @return The installed Wine directory.
     */
    public File getInstalledWineDir() {
        return new File(rootDir, "/opt/installed-wine");
    }

    /**
     * Returns the {@code /tmp} directory inside the image.
     * @return The temporary directory.
     */
    public File getTmpDir() {
        return new File(rootDir, "/tmp");
    }

    /**
     * Returns the {@code /usr/lib} directory inside the image.
     * @return The lib directory.
     */
    public File getLibDir() { return new File(rootDir, "/usr/lib"); }

    /**
     * Returns the {@code /usr/bin} directory inside the image.
     * @return The bin directory.
     */
    public File getBinDir() { return new File(rootDir, "/usr/bin"); }

    /**
     * Returns the {@code /usr/share} directory inside the image.
     * @return The share directory.
     */
    public File getShareDir() {
        return new File(rootDir, "/usr/share");
    }

    /**
     * Returns the 32-bit glibc library directory ({@code /usr/lib/arm-linux-gnueabihf}).
     * @return The 32-bit glibc directory.
     */
    public File getGlibc32Dir() {
        return new File(rootDir, "/usr/lib/arm-linux-gnueabihf");
    }

    /**
     * Returns the 64-bit glibc library directory ({@code /usr/lib}).
     * @return The 64-bit glibc directory.
     */
    public File getGlibc64Dir() {
        return new File(rootDir, "/usr/lib");
    }

    /**
     * Returns the 32-bit library directory ({@code /usr/lib/arm-linux-gnueabihf}).
     * @return The 32-bit library directory.
     */
    public File getLib32Dir() {
        return new File(rootDir, "/usr/lib/arm-linux-gnueabihf");
    }

    /**
     * Returns the 64-bit library directory ({@code /usr/lib}).
     * @return The 64-bit library directory.
     */
    public File getLib64Dir() {
        return new File(rootDir, "/usr/lib");
    }

    /**
     * Returns the {@code /storage} directory inside the image.
     * @return The storage directory.
     */
    public File getStorageDir() {
        return new File(rootDir, "/storage");
    }

    /**
     * Returns the parent of the image root (equivalent to {@code context.getFilesDir()}).
     * @return The parent directory of the image root.
     */
    public File getFilesDir() {
        return rootDir.getParentFile();
    }

    /**
     * Returns the absolute path of the image root directory.
     * @return The root directory path.
     */
    @NonNull
    @Override
    public String toString() {
        return rootDir.getPath();
    }

    /**
     * Returns (and creates if absent) the shared {@code imagefs_shared} directory in app storage.
     * @param context Application context used to resolve the files directory.
     * @return The shared imagefs directory.
     */
    public static File getImageFsSharedDir(Context context) {
        File sharedDir = new File(context.getFilesDir(), "imagefs_shared");
        if (!sharedDir.exists()) {
            sharedDir.mkdirs();
        }
        return sharedDir;
    }
}
