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
     * Root directory for a given variant's imagefs. Both variants can be installed in parallel:
     * files/glibc/imagefs and files/bionic/imagefs.
     */
    public static File getVariantRootDir(Context context, String variant) {
        return new File(context.getFilesDir(), variant + "/imagefs");
    }

    /** Shared Proton directory; opt/<version> in each variant symlinks here. */
    public static File getSharedProtonDir(Context context) {
        return new File(context.getFilesDir(), "imagefs_shared/proton");
    }

    /**
     * Makes files/imagefs a symlink to files/{variant}/imagefs so that ImageFs.find(context)
     * resolves to the given variant. Call before using imagefs when the container variant is known.
     */
    public static void ensureImageFsSymlink(Context context, String variant) {
        File filesDir = context.getFilesDir();
        File link = new File(filesDir, "imagefs");
        File target = getVariantRootDir(context, variant);
        if (!target.exists() || !target.isDirectory()) {
            return;
        }
        try {
            if (FileUtils.isSymlink(link)) {
                File currentTarget = link.getCanonicalFile();
                if (currentTarget.equals(target.getCanonicalFile())) {
                    return;
                }
            } else if (link.exists()) {
                return;
            }
            FileUtils.symlink(target.getAbsolutePath(), link.getAbsolutePath());
        } catch (IOException e) {
            android.util.Log.e("ImageFs", "Failed to create imagefs symlink", e);
        }
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

    public int getVersion() {
        File imgVersionFile = getImgVersionFile();
        return imgVersionFile.exists() ? Integer.parseInt(FileUtils.readLines(imgVersionFile).get(0)) : 0;
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

    public String getVariant() {
        File variantFile = getVariantFile();
        return variantFile.exists() ? FileUtils.readLines(variantFile).get(0) : "";
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

    public String getArch() {
        File archFile = getArchFile();
        return archFile.exists() ? FileUtils.readLines(archFile).get(0) : "";
    }

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

    public File getArchFile() {
        return new File(getConfigDir(), ".arch");
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
}
