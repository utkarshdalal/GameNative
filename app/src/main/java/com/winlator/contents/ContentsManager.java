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
                        // Wine and Proton are functionally equivalent - accept both when scanning either type
                        boolean isWineProtonMatch = (profile != null && profile.type == type) ||
                                (profile != null && type == ContentProfile.ContentType.CONTENT_TYPE_WINE && profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) ||
                                (profile != null && type == ContentProfile.ContentType.CONTENT_TYPE_PROTON && profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE);
                        if (isWineProtonMatch) {
                            profiles.add(profile);
                        }
                    }
                }
            } else {
                Log.d("ContentsManager", "   No directories found (or directory doesn't exist)");
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
            Log.d("ContentsManager", "   Total profiles for type " + type + ": " + profiles.size());
        }
    }

    public void extraContentFile(Uri uri, OnInstallFinishedCallback callback) {
        cleanTmpDir(context);

        File file = getTmpDir(context);

        boolean ret;
        ret = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, context, uri, file);
        if (!ret)
            ret = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, uri, file);
        if (!ret) {
            callback.onFailed(InstallFailedReason.ERROR_BADTAR, null);
            return;
        }

        File proFile = new File(file, PROFILE_NAME);
        if (!proFile.exists()) {
            Log.e("ContentsManager", "profile.json not found");
            callback.onFailed(InstallFailedReason.ERROR_NOPROFILE, null);
            return;
        }

        ContentProfile profile = readProfile(proFile);
        if (profile == null) {
            Log.e("ContentsManager", "Failed to parse profile.json");
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
        // Reject if a version with this name already exists (no version code incrementation)
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
        // For Wine/Proton, normalize directory structure and set executable permissions
        if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE
                || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
            // Normalize lib directory structure: ensure lib/wine/ subdirectory exists
            normalizeWineLibraryStructure(installPath, profile);

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
            // Both Wine and Proton can use either "wine" or "proton" key in profile.json
            if (typeName.equals(ContentProfile.ContentType.CONTENT_TYPE_WINE.toString())
                    || typeName.equals(ContentProfile.ContentType.CONTENT_TYPE_PROTON.toString())) {
                // Try "proton" key first, then fall back to "wine" key for compatibility
                JSONObject wineJSONObject = null;
                if (profileJSONObject.has(ContentProfile.MARK_PROTON)) {
                    wineJSONObject = profileJSONObject.getJSONObject(ContentProfile.MARK_PROTON);
                } else if (profileJSONObject.has(ContentProfile.MARK_WINE)) {
                    wineJSONObject = profileJSONObject.getJSONObject(ContentProfile.MARK_WINE);
                }

                if (wineJSONObject != null) {
                    profile.wineLibPath = wineJSONObject.getString(ContentProfile.MARK_WINE_LIBPATH);
                    profile.wineBinPath = wineJSONObject.getString(ContentProfile.MARK_WINE_BINPATH);
                    profile.winePrefixPack = wineJSONObject.getString(ContentProfile.MARK_WINE_PREFIX_PACK);
                }
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
        if (profilesMap != null)
            return profilesMap.get(type);
        return null;
    }

    public static File getInstallDir(Context context, ContentProfile profile) {
        // Use version name only, no version code suffix
        return new File(getContentTypeDir(context, profile.type), profile.verName);
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
     * Normalizes Wine/Proton library structure to ensure consistent layout.
     * All Wine/Proton installations should have lib/wine/ subdirectory structure
     * regardless of what the profile.json specifies.
     *
     * Expected structure after normalization:
     * - bin/ (executables)
     * - lib/ (shared libraries)
     *   └── wine/ (Wine DLLs by architecture)
     *       ├── i386-windows/
     *       ├── x86_64-windows/
     *       └── aarch64-windows/ (for arm64ec)
     */
    private void normalizeWineLibraryStructure(File installPath, ContentProfile profile) {
        String libPath = profile.wineLibPath != null ? profile.wineLibPath : "lib";
        File actualLibDir = new File(installPath, libPath);
        File expectedLibDir = new File(installPath, "lib");
        File expectedWineLibDir = new File(expectedLibDir, "wine");

        // If libPath is already "lib/wine", we need to restructure
        if (libPath.equals("lib/wine")) {
            // Use timestamp to avoid conflicts with existing temp directories
            File tempWineDir = new File(installPath, "wine_temp_" + System.currentTimeMillis());
            if (actualLibDir.exists()) {
                // Check if temp dir already exists (shouldn't happen with timestamp)
                if (tempWineDir.exists()) {
                    Log.w("ContentsManager", "Temp directory already exists, cleaning it up");
                    FileUtils.delete(tempWineDir);
                }

                // Move lib/wine -> wine_temp
                if (!actualLibDir.renameTo(tempWineDir)) {
                    Log.e("ContentsManager", "Failed to rename lib/wine to temp, aborting normalization");
                    return;
                }

                // Recreate lib/wine and move contents back
                if (!expectedWineLibDir.mkdirs()) {
                    Log.e("ContentsManager", "Failed to create lib/wine directory, attempting rollback");
                    // Try to restore original state
                    if (!tempWineDir.renameTo(actualLibDir)) {
                        Log.e("ContentsManager", "CRITICAL: Failed to rollback, manual intervention may be needed");
                    }
                    return;
                }

                File[] wineContents = tempWineDir.listFiles();
                if (wineContents != null) {
                    boolean allMoved = true;
                    for (File item : wineContents) {
                        File dest = new File(expectedWineLibDir, item.getName());
                        if (!item.renameTo(dest)) {
                            Log.e("ContentsManager", "Failed to move " + item.getName());
                            allMoved = false;
                        }
                    }
                    if (!allMoved) {
                        Log.w("ContentsManager", "Some files failed to move during restructuring");
                    }
                }
                if (tempWineDir.exists()) {
                    FileUtils.delete(tempWineDir);
                }
            }
        }
        // If libPath is "lib" and wine subdirs exist directly in lib/, move them
        else if (libPath.equals("lib")) {
            if (!actualLibDir.exists()) {
                Log.w("ContentsManager", "lib directory does not exist");
                return;
            }

            // Check if wine architecture directories exist directly in lib/
            File[] archDirs = actualLibDir.listFiles(file ->
                file.isDirectory() && (
                    file.getName().equals("i386-windows") ||
                    file.getName().equals("x86_64-windows") ||
                    file.getName().equals("aarch64-windows") ||
                    file.getName().equals("i386-unix") ||
                    file.getName().equals("x86_64-unix") ||
                    file.getName().equals("aarch64-unix")
                )
            );

            if (archDirs != null && archDirs.length > 0) {
                Log.d("ContentsManager", "Found " + archDirs.length + " architecture directories in lib/, moving to lib/wine/");

                // Create lib/wine subdirectory
                if (!expectedWineLibDir.exists() && !expectedWineLibDir.mkdirs()) {
                    Log.e("ContentsManager", "Failed to create lib/wine directory");
                    return;
                }

                // Move each architecture directory into lib/wine/
                for (File archDir : archDirs) {
                    File dest = new File(expectedWineLibDir, archDir.getName());
                    if (archDir.renameTo(dest)) {
                        Log.d("ContentsManager", "Moved " + archDir.getName() + " to lib/wine/");
                    } else {
                        Log.e("ContentsManager", "Failed to move " + archDir.getName());
                    }
                }
            } else {
                Log.d("ContentsManager", "No architecture directories found in lib/, structure already normalized or using different layout");
            }
        }

        Log.d("ContentsManager", "Wine library structure normalization complete");
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
                    Paths.get(getPathFromTemplate(contentFile.target)).toAbsolutePath().normalize().toString()))
                files.add(contentFile);
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
                    case CONTENT_TYPE_TURNIP -> TURNIP_TRUST_FILES;
                    case CONTENT_TYPE_VORTEK -> VORTEK_TRUST_FILES;
                    case CONTENT_TYPE_VIRGL -> VIRGL_TRUST_FILES;
                    case CONTENT_TYPE_DXVK -> DXVK_TRUST_FILES;
                    case CONTENT_TYPE_VKD3D -> VKD3D_TRUST_FILES;
                    case CONTENT_TYPE_BOX64 -> BOX64_TRUST_FILES;
                    case CONTENT_TYPE_WOWBOX64 -> WOWBOX64_TRUST_FILES;
                    case CONTENT_TYPE_FEXCORE -> FEXCORE_TRUST_FILES;
                    default -> new String[0];
                };
                for (String path : paths)
                    pathList.add(Paths.get(getPathFromTemplate(path)).toAbsolutePath().normalize().toString());
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
        File installDir = getInstallDir(context, profile);
        List<ContentProfile> profiles = profilesMap.get(profile.type);
        // Find matching profile by verName and verCode (not by reference equality)
        ContentProfile matchingProfile = null;
        if (profiles != null) {
            for (ContentProfile p : profiles) {
                if (p.verName.equals(profile.verName) && p.verCode == profile.verCode) {
                    matchingProfile = p;
                    break;
                }
            }
        }

        if (matchingProfile != null) {
            FileUtils.delete(installDir);
            profiles.remove(matchingProfile);
            syncContents();
        }
    }

    public static String getEntryName(ContentProfile profile) {
        return profile.type.toString() + '-' + profile.verName + '-' + profile.verCode;
    }

    public ContentProfile getProfileByEntryName(String entryName) {
        Log.d("ContentsManager", "🔍 getProfileByEntryName called with: '" + entryName + "'");

        // Initialize profilesMap if needed (first call before syncContents)
        if (profilesMap == null) {
            Log.d("ContentsManager", "   profilesMap is null, calling syncContents()");
            syncContents();
        }

        // Try to determine type from the entry name (supports prefixed builds like "GE-Proton")
        ContentProfile.ContentType type = null;
        String lowerVersionName = entryName.toLowerCase();
        if (lowerVersionName.contains("proton")) {
            type = ContentProfile.ContentType.CONTENT_TYPE_PROTON;
        } else if (lowerVersionName.contains("wine")) {
            type = ContentProfile.ContentType.CONTENT_TYPE_WINE;
        } else {
            // Try other types
            int firstDash = entryName.indexOf('-');
            if (firstDash > 0) {
                String possibleType = entryName.substring(0, firstDash);
                type = ContentProfile.ContentType.getTypeByName(possibleType);
            }
        }

        Log.d("ContentsManager", "   Detected ContentType: " + type);

        if (type != null && profilesMap.get(type) != null) {
            List<ContentProfile> profiles = profilesMap.get(type);
            Log.d("ContentsManager", "   Found " + profiles.size() + " profiles of type " + type);
    
            String keyLower = lowerVersionName;
    
            for (ContentProfile profile : profiles) {
                String verName = (profile.verName != null) ? profile.verName : "";
                String verLower = verName.toLowerCase();
    
                String typeAndVer = profile.type.toString() + "-" + verName;
                String typeAndVerLower = typeAndVer.toLowerCase();
    
                String fullEntry = ContentsManager.getEntryName(profile);
                String fullEntryLower = fullEntry.toLowerCase();
    
                Log.d(
                    "ContentsManager",
                    "   Checking profile: verName='" + profile.verName +
                            "', typeAndVer='" + typeAndVer +
                            "', fullEntry='" + fullEntry + "'"
                );
    
                if (keyLower.equals(verLower) ||
                    keyLower.equals(typeAndVerLower) ||
                    keyLower.equals(fullEntryLower)) {
                    Log.d("ContentsManager", "   ✅ MATCH FOUND!");
                    return profile;
                }
            }
    
            Log.d("ContentsManager", "   ❌ No matching profile found in primary lookup");
        } else {
            Log.d("ContentsManager", "   ❌ Type is null or no profiles for this type");
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
