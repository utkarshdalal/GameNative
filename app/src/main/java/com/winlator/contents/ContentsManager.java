package com.winlator.contents;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;

import com.winlator.core.FileUtils;
import com.winlator.core.TarCompressorUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class ContentsManager {

    public static final String PROFILE_NAME = "profile.json";
    public static final String REMOTE_PROFILES_URL = "https://raw.githubusercontent.com/longjunyu2/winlator/main/content/metadata.json";
    public static final String[] TURNIP_TRUST_FILES = {"${libdir}/libvulkan_freedreno.so", "${libdir}/libvulkan.so.1",
        "${sharedir}/vulkan/icd.d/freedreno_icd.aarch64.json", "${libdir}/libGL.so.1", "${libdir}/libglapi.so.0"};
    public static final String[] VORTEK_TRUST_FILES = {"${libdir}/libvulkan_vortek.so", "${libdir}/libvulkan_freedreno.so",
        "${sharedir}/vulkan/icd.d/vortek_icd.aarch64.json"};
    public static final String[] VIRGL_TRUST_FILES = {"${libdir}/libGL.so.1", "${libdir}/libglapi.so.0"};
    public static final String[] DXVK_TRUST_FILES = {"${system32}/d3d8.dll", "${system32}/d3d9.dll", "${system32}/d3d10.dll", "${system32}/d3d10_1.dll",
        "${system32}/d3d10core.dll", "${system32}/d3d11.dll", "${system32}/dxgi.dll", "${syswow64}/d3d8.dll", "${syswow64}/d3d9.dll", "${syswow64}/d3d10.dll",
        "${syswow64}/d3d10_1.dll", "${syswow64}/d3d10core.dll", "${syswow64}/d3d11.dll", "${syswow64}/dxgi.dll"};
    public static final String[] VKD3D_TRUST_FILES = {"${system32}/d3d12core.dll", "${system32}/d3d12.dll",
        "${syswow64}/d3d12core.dll", "${syswow64}/d3d12.dll"};
    public static final String[] BOX64_TRUST_FILES = {"${localbin}/box64", "${bindir}/box64"};
    public static final String[] WOWBOX64_TRUST_FILES = {"${system32}/wowbox64.dll"};
    public static final String[] FEXCORE_TRUST_FILES = {"${system32}/libwow64fex.dll", "${system32}/libarm64ecfex.dll"};
    private Map<String, String> dirTemplateMap;
    private Map<ContentProfile.ContentType, List<String>> trustedFilesMap;

    public enum InstallFailedReason {
        ERROR_NOSPACE,
        ERROR_BADTAR,
        ERROR_NOPROFILE,
        ERROR_BADPROFILE,
        ERROR_MISSINGFILES,
        ERROR_EXIST,
        ERROR_UNTRUSTPROFILE,
        ERROR_UNKNOWN
    }

    public enum ContentDirName {
        CONTENT_MAIN_DIR_NAME("contents"),
        CONTENT_WINE_DIR_NAME("wine"),
        CONTENT_TURNIP_DIR_NAME("turnip"),
        CONTENT_VIRGL_DIR_NAME("virgl"),
        CONTENT_DXVK_DIR_NAME("dxvk"),
        CONTENT_VKD3D_DIR_NAME("vkd3d"),
        CONTENT_BOX64_DIR_NAME("box64");

        private String name;

        ContentDirName(String name) {
            this.name = name;
        }

        @NonNull
        @Override
        public String toString() {
            return name;
        }
    }

    private final Context context;

    private HashMap<ContentProfile.ContentType, List<ContentProfile>> profilesMap;

    private ArrayList<ContentProfile> remoteProfiles;

    public ContentsManager(Context context) {
        this.context = context;
    }

    public interface OnInstallFinishedCallback {

        void onFailed(InstallFailedReason reason, Exception e);

        void onSucceed(ContentProfile profile);
    }

    public void setRemoteProfiles(String json) {
        try {
            remoteProfiles = new ArrayList<>();
            JSONArray content = new JSONArray(json);
            for (int i = 0; i < content.length(); i++) {
                try {
                    JSONObject object = content.getJSONObject(i);
                    ContentProfile remoteProfile = new ContentProfile();
                    remoteProfile.remoteUrl = object.getString("remoteUrl");
                    remoteProfile.type = ContentProfile.ContentType.getTypeByName(object.getString("type"));
                    remoteProfile.verName = object.getString("verName");
                    remoteProfile.verCode = object.getInt("verCode");
                    remoteProfiles.add(remoteProfile);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        syncContents();
    }

    public void syncContents() {
        profilesMap = new HashMap<>();
        for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
            LinkedList<ContentProfile> profiles = new LinkedList<>();
            profilesMap.put(type, profiles);

            File typeFile = getContentTypeDir(context, type);
            File[] fileList = typeFile.listFiles();
            if (fileList != null) {
                for (File file : fileList) {
                    File proFile = new File(file, PROFILE_NAME);
                    if (proFile.exists() && proFile.isFile()) {
                        ContentProfile profile = readProfile(proFile);
                        if (profile != null) {
                            profiles.add(profile);
                        }
                    }
                }
            }

            if (remoteProfiles != null) {
                for (ContentProfile remote : remoteProfiles) {
                    if (remote.type == type) {
                        boolean exists = false;
                        for (ContentProfile profile : profiles) {
                            if ((profile.verName.compareTo(remote.verName) == 0) && (profile.verCode == remote.verCode)) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            profiles.add(remote);
                        }
                    }
                }
            }
        }
    }

    public void extraContentFile(Uri uri, OnInstallFinishedCallback callback) {
        cleanTmpDir(context);

        File file = getTmpDir(context);

        boolean ret;
        ret = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, context, uri, file);
        if (!ret) {
            ret = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, uri, file);
        }
        if (!ret) {
            callback.onFailed(InstallFailedReason.ERROR_BADTAR, null);
            return;
        }

        File proFile = new File(file, PROFILE_NAME);
        if (!proFile.exists()) {
            callback.onFailed(InstallFailedReason.ERROR_NOPROFILE, null);
            return;
        }

        ContentProfile profile = readProfile(proFile);
        if (profile == null) {
            callback.onFailed(InstallFailedReason.ERROR_BADPROFILE, null);
            return;
        }

        String imagefsPath = context.getFilesDir().getAbsolutePath() + "/imagefs";
        for (ContentProfile.ContentFile contentFile : profile.fileList) {
            File tmpFile = new File(file, contentFile.source);
            if (!tmpFile.exists() || !tmpFile.isFile() || !isSubPath(file.getAbsolutePath(), tmpFile.getAbsolutePath())) {
                callback.onFailed(InstallFailedReason.ERROR_MISSINGFILES, null);
                return;
            }

            String realPath = getPathFromTemplate(contentFile.target);
            if (!isSubPath(imagefsPath, realPath) || isSubPath(ContentsManager.getContentDir(context).getAbsolutePath(), realPath) || realPath.contains("dosdevices")) {
                callback.onFailed(InstallFailedReason.ERROR_UNTRUSTPROFILE, null);
                return;
            }
        }

        if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
            File bin = new File(file, profile.wineBinPath);
            File lib = new File(file, profile.wineLibPath);
            File cp = new File(file, profile.winePrefixPack);

            if (!bin.exists() || !bin.isDirectory() || !lib.exists() || !lib.isDirectory() || !cp.exists() || !cp.isFile()) {
                callback.onFailed(InstallFailedReason.ERROR_MISSINGFILES, null);
                return;
            }
        }

        callback.onSucceed(profile);
    }

    public void finishInstallContent(ContentProfile profile, OnInstallFinishedCallback callback) {
        // Check if a version with this name already exists and find next available version code
        int nextVerCode = findNextAvailableVersionCode(profile.verName, profile.type);
        if (nextVerCode > 0) {
            // A version with this name exists, use incremented version code
            profile.verCode = nextVerCode;
        }

        File installPath = getInstallDir(context, profile);
        if (installPath.exists()) {
            callback.onFailed(InstallFailedReason.ERROR_EXIST, null);
            return;
        }

        if (!installPath.mkdirs()) {
            callback.onFailed(InstallFailedReason.ERROR_UNKNOWN, null);
            return;
        }

        if (!getTmpDir(context).renameTo(installPath)) {
            callback.onFailed(InstallFailedReason.ERROR_UNKNOWN, null);
            return;
        }

        // For Wine/Proton, set executable permissions on binaries
        if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE
                || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
            File binDir = new File(installPath, profile.wineBinPath);
            if (binDir.exists() && binDir.isDirectory()) {
                Log.d("ContentsManager", "Setting executable permissions for Wine/Proton binaries in: " + binDir.getPath());
                setExecutablePermissionsRecursive(binDir);
            }
        }

        callback.onSucceed(profile);
    }

    public ContentProfile readProfile(File file) {
        try {
            ContentProfile profile = new ContentProfile();
            JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
            String typeName = profileJSONObject.getString(ContentProfile.MARK_TYPE);
            String verName = profileJSONObject.getString(ContentProfile.MARK_VERSION_NAME);
            int verCode = profileJSONObject.getInt(ContentProfile.MARK_VERSION_CODE);
            String desc = profileJSONObject.getString(ContentProfile.MARK_DESC);

            JSONArray fileJSONArray = profileJSONObject.getJSONArray(ContentProfile.MARK_FILE_LIST);
            List<ContentProfile.ContentFile> fileList = new ArrayList<>();
            for (int i = 0; i < fileJSONArray.length(); i++) {
                JSONObject contentFileJSONObject = fileJSONArray.getJSONObject(i);
                ContentProfile.ContentFile contentFile = new ContentProfile.ContentFile();
                contentFile.source = contentFileJSONObject.getString(ContentProfile.MARK_FILE_SOURCE);
                contentFile.target = contentFileJSONObject.getString(ContentProfile.MARK_FILE_TARGET);
                fileList.add(contentFile);
            }
            if (typeName.equals(ContentProfile.ContentType.CONTENT_TYPE_WINE.toString())) {
                JSONObject wineJSONObject = profileJSONObject.getJSONObject(ContentProfile.MARK_WINE);
                profile.wineLibPath = wineJSONObject.getString(ContentProfile.MARK_WINE_LIBPATH);
                profile.wineBinPath = wineJSONObject.getString(ContentProfile.MARK_WINE_BINPATH);
                profile.winePrefixPack = wineJSONObject.getString(ContentProfile.MARK_WINE_PREFIX_PACK);
            } else if (typeName.equals(ContentProfile.ContentType.CONTENT_TYPE_PROTON.toString())) {
                JSONObject protonJSONObject = profileJSONObject.getJSONObject(ContentProfile.MARK_PROTON);
                profile.wineLibPath = protonJSONObject.getString(ContentProfile.MARK_PROTON_LIBPATH);
                profile.wineBinPath = protonJSONObject.getString(ContentProfile.MARK_PROTON_BINPATH);
                profile.winePrefixPack = protonJSONObject.getString(ContentProfile.MARK_PROTON_PREFIX_PACK);
            }

            profile.type = ContentProfile.ContentType.getTypeByName(typeName);

            // For Wine/Proton, ensure type is included in version name
            if ((typeName.equals(ContentProfile.ContentType.CONTENT_TYPE_WINE.toString())
                    || typeName.equals(ContentProfile.ContentType.CONTENT_TYPE_PROTON.toString()))
                    && !verName.toLowerCase().startsWith(typeName.toLowerCase())) {
                profile.verName = typeName.toLowerCase() + "-" + verName;
            } else {
                profile.verName = verName;
            }

            profile.verCode = verCode;
            profile.desc = desc;
            profile.fileList = fileList;
            return profile;
        } catch (Exception e) {
            return null;
        }
    }

    public List<ContentProfile> getProfiles(ContentProfile.ContentType type) {
        if (profilesMap != null) {
            return profilesMap.get(type);
        }
        return null;
    }

    /**
     * Find the next available version code for a given version name and type.
     * Returns 0 if no existing version found, otherwise returns max + 1.
     */
    private int findNextAvailableVersionCode(String verName, ContentProfile.ContentType type) {
        List<ContentProfile> profiles = getProfiles(type);
        if (profiles == null || profiles.isEmpty()) {
            return 0;
        }

        int maxVerCode = -1;
        for (ContentProfile profile : profiles) {
            if (profile.verName.equals(verName) && profile.verCode > maxVerCode) {
                maxVerCode = profile.verCode;
            }
        }

        return maxVerCode + 1;
    }

    public static File getInstallDir(Context context, ContentProfile profile) {
        return new File(getContentTypeDir(context, profile.type), profile.verName + "-" + profile.verCode);
    }

    public static File getContentDir(Context context) {
        return new File(context.getFilesDir(), ContentDirName.CONTENT_MAIN_DIR_NAME.toString());
    }

    public static File getContentTypeDir(Context context, ContentProfile.ContentType type) {
        // Wine/Proton must be installed inside imagefs/opt/ to run inside proot environment
        // This is required for ARM64EC Wine to handle mmap(PROT_EXEC) calls properly
        if (type == ContentProfile.ContentType.CONTENT_TYPE_WINE
                || type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
            return new File(context.getFilesDir(), "imagefs/opt");
        }
        return new File(getContentDir(context), type.toString());
    }

    public static File getTmpDir(Context context) {
        return new File(context.getFilesDir(), "tmp/" + ContentDirName.CONTENT_MAIN_DIR_NAME);
    }

    public static File getSourceFile(Context context, ContentProfile profile, String path) {
        return new File(getInstallDir(context, profile), path);
    }

    public static void cleanTmpDir(Context context) {
        File file = getTmpDir(context);
        FileUtils.delete(file);
        file.mkdirs();
    }

    /**
     * Recursively sets executable permissions (0755) on all files in a
     * directory. This is needed for Wine/Proton binaries to run on Android.
     */
    private void setExecutablePermissionsRecursive(File dir) {
        if (!dir.exists()) {
            return;
        }

        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                setExecutablePermissionsRecursive(file);
            } else if (file.isFile()) {
                // Set executable permissions on all files in bin/ directory
                FileUtils.chmod(file, 0755);
                Log.d("ContentsManager", "Set chmod 0755 on: " + file.getName());
            }
        }
    }

    public List<ContentProfile.ContentFile> getUnTrustedContentFiles(ContentProfile profile) {
        createTrustedFilesMap();
        List<ContentProfile.ContentFile> files = new ArrayList<>();
        for (ContentProfile.ContentFile contentFile : profile.fileList) {
            if (!trustedFilesMap.get(profile.type).contains(
                    Paths.get(getPathFromTemplate(contentFile.target)).toAbsolutePath().normalize().toString())) {
                files.add(contentFile);
            }
        }
        return files;
    }

    private boolean isSubPath(String parent, String child) {
        return Paths.get(child).toAbsolutePath().normalize().startsWith(Paths.get(parent).toAbsolutePath().normalize());
    }

    private void createDirTemplateMap() {
        if (dirTemplateMap == null) {
            dirTemplateMap = new HashMap<>();
            String imagefsPath = context.getFilesDir().getAbsolutePath() + "/imagefs";
            String drivecPath = imagefsPath + "/home/xuser/.wine/drive_c";
            dirTemplateMap.put("${libdir}", imagefsPath + "/usr/lib");
            dirTemplateMap.put("${system32}", drivecPath + "/windows/system32");
            dirTemplateMap.put("${syswow64}", drivecPath + "/windows/syswow64");
            dirTemplateMap.put("${localbin}", imagefsPath + "/usr/local/bin");
            dirTemplateMap.put("${bindir}", imagefsPath + "/usr/bin");
            dirTemplateMap.put("${sharedir}", imagefsPath + "/usr/share");
        }
    }

    private void createTrustedFilesMap() {
        if (trustedFilesMap == null) {
            trustedFilesMap = new HashMap<>();
            for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
                List<String> pathList = new ArrayList<>();
                trustedFilesMap.put(type, pathList);

                String[] paths = switch (type) {
                    case CONTENT_TYPE_TURNIP ->
                        TURNIP_TRUST_FILES;
                    case CONTENT_TYPE_VORTEK ->
                        VORTEK_TRUST_FILES;
                    case CONTENT_TYPE_VIRGL ->
                        VIRGL_TRUST_FILES;
                    case CONTENT_TYPE_DXVK ->
                        DXVK_TRUST_FILES;
                    case CONTENT_TYPE_VKD3D ->
                        VKD3D_TRUST_FILES;
                    case CONTENT_TYPE_BOX64 ->
                        BOX64_TRUST_FILES;
                    case CONTENT_TYPE_WOWBOX64 ->
                        WOWBOX64_TRUST_FILES;
                    case CONTENT_TYPE_FEXCORE ->
                        FEXCORE_TRUST_FILES;
                    default ->
                        new String[0];
                };
                for (String path : paths) {
                    pathList.add(Paths.get(getPathFromTemplate(path)).toAbsolutePath().normalize().toString());
                }
            }
        }
    }

    private String getPathFromTemplate(String path) {
        createDirTemplateMap();
        String realPath = path;
        for (String key : dirTemplateMap.keySet()) {
            realPath = realPath.replace(key, dirTemplateMap.get(key));
        }
        return realPath;
    }

    public void removeContent(ContentProfile profile) {
        if (profilesMap.get(profile.type).contains(profile)) {
            FileUtils.delete(getInstallDir(context, profile));
            profilesMap.get(profile.type).remove(profile);
            syncContents();
        }
    }

    public static String getEntryName(ContentProfile profile) {
        return profile.type.toString() + '-' + profile.verName + '-' + profile.verCode;
    }

    public ContentProfile getProfileByEntryName(String entryName) {
        int firstDashIndex = entryName.indexOf('-');
        int lastDashIndex = entryName.lastIndexOf('-');

        try {
            String typeName = entryName.substring(0, firstDashIndex);
            String versionName = entryName.substring(firstDashIndex + 1, lastDashIndex);
            String versionCode = entryName.substring(lastDashIndex + 1);

            ContentProfile.ContentType type = ContentProfile.ContentType.getTypeByName(typeName);
            if (type != null && profilesMap.get(type) != null) {
                for (ContentProfile profile : profilesMap.get(type)) {
                    if (versionName.equals(profile.verName) && Integer.parseInt(versionCode) == profile.verCode) {
                        return profile;
                    }
                }
            }
        } catch (Exception e) {
        }

        // Fallback: Try Wine and Proton lists if entry name doesn't have type prefix
        // This handles legacy identifiers like "proton-10-arm64ec-0"
        try {
            int lastDash = entryName.lastIndexOf('-');
            if (lastDash > 0) {
                String verName = entryName.substring(0, lastDash);
                String verCodeStr = entryName.substring(lastDash + 1);
                int verCode = Integer.parseInt(verCodeStr);

                // Check Wine list
                if (profilesMap.get(ContentProfile.ContentType.CONTENT_TYPE_WINE) != null) {
                    for (ContentProfile profile : profilesMap.get(ContentProfile.ContentType.CONTENT_TYPE_WINE)) {
                        if (verName.equals(profile.verName) && verCode == profile.verCode) {
                            return profile;
                        }
                    }
                }

                // Check Proton list
                if (profilesMap.get(ContentProfile.ContentType.CONTENT_TYPE_PROTON) != null) {
                    for (ContentProfile profile : profilesMap.get(ContentProfile.ContentType.CONTENT_TYPE_PROTON)) {
                        if (verName.equals(profile.verName) && verCode == profile.verCode) {
                            return profile;
                        }
                    }
                }
            }
        } catch (Exception e) {
        }

        return null;
    }

    public boolean applyContent(ContentProfile profile) {
        if (profile.type != ContentProfile.ContentType.CONTENT_TYPE_WINE && profile.type != ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
            Log.d("ContentsManager", "if condition");
            for (ContentProfile.ContentFile contentFile : profile.fileList) {
                File targetFile = new File(getPathFromTemplate(contentFile.target));
                File sourceFile = new File(getInstallDir(context, profile), contentFile.source);

                targetFile.delete();
                FileUtils.copy(sourceFile, targetFile);

                if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_BOX64) {
                    Log.d("ContentsManager", "found box64 profile type - running chmod on " + targetFile);
                    FileUtils.chmod(targetFile, 0755);
                }
            }
        } else {
            Log.d("ContentsManager", "else condition - doing nothing");
            // TODO: do nothing?
        }
        return true;
    }
}
