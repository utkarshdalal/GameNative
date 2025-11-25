package com.winlator.core;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.NonNull;

import com.winlator.contents.ContentProfile;
import com.winlator.contents.ContentsManager;
import com.winlator.xenvironment.ImageFs;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.gamenative.R;

public class WineInfo implements Parcelable {

    public static final WineInfo MAIN_WINE_VERSION = new WineInfo("wine", "9.2", "x86_64");
    private static final Pattern pattern = Pattern.compile("^(wine|proton|Proton)\\-([0-9]+(?:\\.[0-9]+)*)\\-?([0-9\\.]+)?\\-(x86|x86_64|arm64ec)$");
    public final String version;
    public final String type;
    public String subversion;
    public final String path;
    public String libPath; // Path to lib directory from profile.json (e.g., "lib" or "lib/wine")
    private String arch;

    public WineInfo(String type, String version, String arch) {
        this.type = type;
        this.version = version;
        this.subversion = null;
        this.arch = arch;
        this.path = null;
        this.libPath = null;
    }

    public WineInfo(String type, String version, String subversion, String arch, String path) {
        this.type = type;
        this.version = version;
        this.subversion = subversion != null && !subversion.isEmpty() ? subversion : null;
        this.arch = arch;
        this.path = path;
        this.libPath = null;
    }

    public WineInfo(String type, String version, String arch, String path) {
        this.type = type;
        this.version = version;
        this.arch = arch;
        this.path = path;
        this.libPath = null;
    }

    private WineInfo(Parcel in) {
        type = in.readString();
        version = in.readString();
        subversion = in.readString();
        arch = in.readString();
        path = in.readString();
        libPath = in.readString();
    }

    public String getArch() {
        return arch;
    }

    public void setArch(String arch) {
        this.arch = arch;
    }

    public boolean isWin64() {
        return arch.equals("x86_64") || arch.equals("arm64ec");
    }

    public boolean isArm64EC() {
        return arch.equals("arm64ec");
    }

    public boolean isMainWineVersion() {
        WineInfo other = WineInfo.MAIN_WINE_VERSION;

        boolean pathMatches
                = (path == null && other.path == null)
                || (path != null && path.equals(other.path));

        return type.equals(other.type)
                && version.equals(other.version)
                && arch.equals(other.arch)
                && pathMatches;
    }

    public String getExecutable(Context context, boolean wow64Mode) {
        if (this == MAIN_WINE_VERSION) {
            File wineBinDir = new File(ImageFs.find(context).getRootDir(), "/opt/wine/bin");
            File wineBinFile = new File(wineBinDir, "wine");
            File winePreloaderBinFile = new File(wineBinDir, "wine-preloader");
            FileUtils.copy(new File(wineBinDir, wow64Mode ? "wine-wow64" : "wine32"), wineBinFile);
            FileUtils.copy(new File(wineBinDir, wow64Mode ? "wine-preloader-wow64" : "wine32-preloader"), winePreloaderBinFile);
            FileUtils.chmod(wineBinFile, 0771);
            FileUtils.chmod(winePreloaderBinFile, 0771);
            return wow64Mode ? "wine" : "wine64";
        } else {
            return (new File(path, "/bin/wine64")).isFile() ? "wine64" : "wine";
        }
    }

    public String identifier() {
        if (type.equals("proton")) {
            return "proton-" + fullVersion() + "-" + arch;
        } else {
            return "wine-" + fullVersion() + "-" + arch;
        }
    }

    public String fullVersion() {
        return version + (subversion != null ? "-" + subversion : "");
    }

    @NonNull
    @Override
    public String toString() {
        if (type.equals("proton")) {
            return "Proton " + fullVersion() + (this == MAIN_WINE_VERSION ? " (Custom)" : "");
        } else {
            return "Wine " + fullVersion() + (this == MAIN_WINE_VERSION ? " (Custom)" : "");
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<WineInfo> CREATOR = new Parcelable.Creator<WineInfo>() {
        public WineInfo createFromParcel(Parcel in) {
            return new WineInfo(in);
        }

        public WineInfo[] newArray(int size) {
            return new WineInfo[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(type);
        dest.writeString(version);
        dest.writeString(subversion);
        dest.writeString(arch);
        dest.writeString(path);
        dest.writeString(libPath);
    }

    @NonNull
    public static WineInfo fromIdentifier(Context context, ContentsManager contentsManager, String identifier) {
        ImageFs imageFs = ImageFs.find(context);
        String path = "";

        Log.d("WineInfo", "========================================");
        Log.d("WineInfo", "Creating WineInfo from identifier: '" + identifier + "'");
        Log.d("WineInfo", "========================================");

        if (identifier.equals(MAIN_WINE_VERSION.identifier())) {
            Log.d("WineInfo", "Using MAIN_WINE_VERSION");
            return new WineInfo(MAIN_WINE_VERSION.type, MAIN_WINE_VERSION.version, MAIN_WINE_VERSION.arch, null);
        }

        // Try to lookup with version code appended (identifier might be missing -0 at the end)
        Log.d("WineInfo", "Trying lookup with: '" + identifier + "-0'");
        ContentProfile wineProfile = contentsManager.getProfileByEntryName(identifier + "-0");

        // Fallback to identifier as-is if not found
        if (wineProfile == null) {
            Log.d("WineInfo", "First lookup failed, trying: '" + identifier + "'");
            wineProfile = contentsManager.getProfileByEntryName(identifier);
        }

        Log.d("WineInfo", "Wine profile lookup result: " + (wineProfile != null ? "FOUND" : "NULL"));
        Matcher matcher = pattern.matcher(identifier);

        if (matcher.find()) {
            String[] wineVersions = context.getResources().getStringArray(R.array.bionic_wine_entries);
            for (String wineVersion : wineVersions) {
                if (wineVersion.contains(identifier)) {
                    path = imageFs.getRootDir().getPath() + "/opt/" + identifier;
                    break;
                }
            }

            if (wineProfile != null && (wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE || wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON)) {
                path = ContentsManager.getInstallDir(context, wineProfile).getPath();
                Log.d("WineInfo", "Wine path for wineinfo: " + path);
                
                // Create WineInfo and set libPath from profile
                WineInfo wineInfo = new WineInfo(matcher.group(1), matcher.group(2), matcher.group(4), path);
                wineInfo.libPath = wineProfile.wineLibPath;
                Log.d("WineInfo", "Wine libPath from profile: " + wineInfo.libPath);
                return wineInfo;
            } else {
                Log.d("WineInfo", "Wine path for wineinfo is null - wineProfile not found or not Wine/Proton type");
            }

            return new WineInfo(matcher.group(1), matcher.group(2), matcher.group(4), path);
        } else {
            return new WineInfo(MAIN_WINE_VERSION.type, MAIN_WINE_VERSION.version, MAIN_WINE_VERSION.arch, null);
        }
    }

    public static boolean isMainWineVersion(String wineVersion) {
        return wineVersion == null || wineVersion.equals(MAIN_WINE_VERSION.identifier());
    }
}
