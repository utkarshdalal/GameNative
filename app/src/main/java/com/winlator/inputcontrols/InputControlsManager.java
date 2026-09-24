package com.winlator.inputcontrols;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.JsonReader;
import android.util.Log;

import com.winlator.PrefManager;
import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InputControlsManager {
    public static final int MAX_PROFILE_NAME_LENGTH = 80;
    private static final int MAX_PROFILE_ID = 1_000_000_000;
    private static final Pattern PROFILE_FILE_PATTERN = Pattern.compile("controls-(\\d+)\\.icp");
    private static final Object PROFILE_ID_LOCK = new Object();
    private static final Set<String> RESERVED_PROFILE_PATHS = new HashSet<>();

    private final Context context;
    private ArrayList<ControlsProfile> profiles;
    private int maxProfileId;
    private boolean profilesLoaded = false;

    public InputControlsManager(Context context) {
        this.context = context;
    }

    public static File getProfilesDir(Context context) {
        File profilesDir = new File(context.getFilesDir(), "profiles");
        if (!profilesDir.isDirectory()) profilesDir.mkdir();
        return profilesDir;
    }

    public ArrayList<ControlsProfile> getProfiles() {
        return getProfiles(false);
    }

    public ArrayList<ControlsProfile> getProfiles(boolean ignoreTemplates) {
        if (!profilesLoaded) loadProfiles();
        ArrayList<ControlsProfile> visibleProfiles = new ArrayList<>();
        for (ControlsProfile profile : profiles) {
            if (!profile.isListed()) continue;
            if (ignoreTemplates && profile.isTemplate()) continue;
            visibleProfiles.add(profile);
        }
        return visibleProfiles;
    }

    public ArrayList<ControlsProfile> getAllProfiles() {
        if (!profilesLoaded) loadProfiles();
        return new ArrayList<>(profiles);
    }

    private void copyAssetProfilesIfNeeded() {
        File profilesDir = InputControlsManager.getProfilesDir(context);
        File[] files = profilesDir.listFiles();
        boolean hasInstalledProfile = false;
        if (files != null) {
            for (File file : files) {
                if (loadInstalledProfile(file) != null) {
                    hasInstalledProfile = true;
                    break;
                }
            }
        }
        if (!hasInstalledProfile) {
            FileUtils.copy(context, "inputcontrols/profiles", profilesDir);
            return;
        }

        PrefManager.init(context);
        String newVersion = String.valueOf(AppUtils.getVersionCode(context));
        String oldVersion = PrefManager.getString("inputcontrols_app_version", "0");
        if (oldVersion.equals(newVersion)) return;
        PrefManager.putString("inputcontrols_app_version", newVersion);

        if (files == null) return;

        try {
            AssetManager assetManager = context.getAssets();
            String[] assetFiles = assetManager.list("inputcontrols/profiles");

            // Pre-compute max local ID for assigning new profile IDs
            int nextNewId = 0;
            for (File f : files) {
                ControlsProfile p = loadInstalledProfile(f);
                if (p != null) {
                    nextNewId = Math.max(
                            nextNewId,
                            Math.max(p.id, p.getMaxReferencedProfileId()) + 1
                    );
                }
            }

            for (String assetFile : assetFiles) {
                String assetPath = "inputcontrols/profiles/"+assetFile;
                ControlsProfile originProfile = loadProfile(context, assetManager.open(assetPath));

                File targetFile = null;
                for (File file : files) {
                    ControlsProfile targetProfile = loadInstalledProfile(file);
                    if (originProfile == null || targetProfile == null) continue;
                    if (originProfile.id == targetProfile.id && originProfile.getName().equals(targetProfile.getName())) {
                        targetFile = file;
                        break;
                    }
                }

                if (targetFile != null) {
                    FileUtils.copy(context, assetPath, targetFile);
                } else if (originProfile != null) {
                    // New asset profile — add if not already present
                    File defaultFile = new File(profilesDir, assetFile);
                    if (!defaultFile.exists()) {
                        FileUtils.copy(context, assetPath, defaultFile);
                    } else {
                        // Filename occupied by a different profile — check if
                        // this asset profile (by name) already exists under any ID
                        boolean alreadyExists = false;
                        for (File file2 : files) {
                            ControlsProfile p = loadInstalledProfile(file2);
                            if (p != null && p.getName().equals(originProfile.getName())) {
                                alreadyExists = true;
                                break;
                            }
                        }
                        if (!alreadyExists) {
                            if (nextNewId > MAX_PROFILE_ID) {
                                throw new IOException("Control profile ID limit reached");
                            }
                            int newId = nextNewId++;
                            File freeFile = ControlsProfile.getProfileFile(context, newId);
                            try {
                                String json = FileUtils.readString(context, assetPath);
                                JSONObject data = new JSONObject(json);
                                data.put("id", newId);
                                if (!FileUtils.writeString(freeFile, data.toString())) {
                                    throw new IOException("Unable to write built-in control profile");
                                }
                            } catch (Exception e) {
                                Log.w("InputControlsManager", "Failed to create profile '" + originProfile.getName() + "' (newId=" + newId + ", file=" + freeFile + ")", e);
                            }
                        }
                    }
                }
            }

            // Fix if controls-0.icp not exists
            File file = ControlsProfile.getProfileFile(context, 0);
            if (!file.isFile()) {
                FileUtils.copy(context, "inputcontrols/profiles/controls-0.icp", file);
            }
        }
        catch (IOException e) {}
    }

    private void loadProfiles() {
        File profilesDir = InputControlsManager.getProfilesDir(context);
        copyAssetProfilesIfNeeded();

        ArrayList<ControlsProfile> profiles = new ArrayList<>();
        Set<Integer> loadedIds = new HashSet<>();
        maxProfileId = 0;
        File[] files = profilesDir.listFiles();
        if (files != null) {
            for (File file : files) {
                ControlsProfile profile = loadInstalledProfile(file);
                if (profile == null) continue;
                if (!loadedIds.add(profile.id)) {
                    Log.w("InputControlsManager", "Ignoring duplicate control profile ID " + profile.id);
                    continue;
                }
                profiles.add(profile);
                maxProfileId = Math.max(maxProfileId, Math.max(profile.id, profile.getMaxReferencedProfileId()));
            }
        }

        Collections.sort(profiles);
        this.profiles = profiles;
        profilesLoaded = true;
    }

    public void reloadProfiles() {
        profilesLoaded = false;
        loadProfiles();
    }

    public synchronized int nextProfileId() {
        synchronized (PROFILE_ID_LOCK) {
            if (!profilesLoaded) loadProfiles();
            File candidate;
            do {
                if (maxProfileId >= MAX_PROFILE_ID) {
                    throw new IllegalStateException("Control profile ID limit reached");
                }
                maxProfileId++;
                candidate = ControlsProfile.getProfileFile(context, maxProfileId);
            } while (candidate.exists() || !RESERVED_PROFILE_PATHS.add(candidate.getAbsolutePath()));
            // Keep the reservation for this process lifetime so another manager with
            // a stale snapshot cannot reuse the ID before or after the writer commits.
            return maxProfileId;
        }
    }

    public synchronized ControlsProfile createProfile(String name) {
        if (!profilesLoaded) loadProfiles();
        String normalizedName = normalizeProfileName(name);
        if (hasVisibleProfileNamed(normalizedName, -1)) {
            throw new IllegalArgumentException("A control profile with that name already exists");
        }
        ControlsProfile profile = new ControlsProfile(context, nextProfileId());
        profile.setName(normalizedName);
        if (!profile.save()) throw new IllegalStateException("Unable to save control profile");
        profiles.add(profile);
        return profile;
    }

    public synchronized ControlsProfile duplicateProfile(ControlsProfile source) {
        if (source == null) throw new IllegalArgumentException("Missing source control profile");
        if (!profilesLoaded) loadProfiles();
        String newName;
        for (int i = 1;;i++) {
            String suffix = " (" + i + ")";
            int baseLimit = Math.max(1, MAX_PROFILE_NAME_LENGTH - suffix.length());
            String baseName = source.getName().substring(0, Math.min(source.getName().length(), baseLimit));
            newName = baseName + suffix;
            if (!hasVisibleProfileNamed(newName, -1)) break;
        }

        int newId = nextProfileId();
        File newFile = ControlsProfile.getProfileFile(context, newId);

        try {
            JSONObject data = new JSONObject(FileUtils.readString(ControlsProfile.getProfileFile(context, source.id)));
            data.put("id", newId);
            data.put("name", newName);
            if (data.has("template")) data.remove("template");
            data.put("listed", true);
            data.remove("libraryProfileId");
            data.remove("gameOwnerId");
            data.remove("sectionSources");
            if (!FileUtils.writeString(newFile, data.toString())) {
                throw new IOException("Unable to write duplicated control profile");
            }

            ControlsProfile profile = loadProfile(context, newFile);
            if (profile == null) throw new IOException("Unable to read duplicated control profile");
            profiles.add(profile);
            return profile;
        }
        catch (Exception e) {
            if (newFile.isFile() && !newFile.delete()) {
                Log.w("InputControlsManager", "Unable to remove incomplete duplicate " + newFile);
            }
            throw new IllegalStateException("Unable to duplicate control profile", e);
        }
    }

    public synchronized boolean removeProfile(ControlsProfile profile) {
        if (profile == null) return false;
        if (!profilesLoaded) loadProfiles();
        File file = ControlsProfile.getProfileFile(context, profile.id);
        if (!file.isFile() || !file.delete()) return false;
        profiles.removeIf(candidate -> candidate.id == profile.id);
        return true;
    }

    public synchronized ControlsProfile importProfile(JSONObject data) {
        try {
            if (!data.has("name")) return null;
            if (!profilesLoaded) loadProfiles();
            data = new JSONObject(data.toString());
            int newId = nextProfileId();
            File newFile = ControlsProfile.getProfileFile(context, newId);
            data.put("id", newId);
            data.put("listed", true);
            data.remove("libraryProfileId");
            data.remove("gameOwnerId");
            data.remove("sectionSources");

            String baseName = normalizeProfileName(data.optString("name", "Imported Profile"));
            String uniqueName = baseName;
            for (int suffix = 1; hasVisibleProfileNamed(uniqueName, -1); suffix++) {
                String suffixText = " (" + suffix + ")";
                int baseLimit = Math.max(1, MAX_PROFILE_NAME_LENGTH - suffixText.length());
                uniqueName = baseName.substring(0, Math.min(baseName.length(), baseLimit)) + suffixText;
            }
            data.put("name", uniqueName);
            if (!FileUtils.writeString(newFile, data.toString())) {
                throw new IOException("Unable to write imported control profile");
            }
            ControlsProfile newProfile = loadProfile(context, newFile);
            if (newProfile == null) {
                if (!newFile.delete()) Log.w("InputControlsManager", "Unable to remove invalid import " + newFile);
                throw new IOException("Unable to read imported control profile");
            }
            profiles.add(newProfile);
            return newProfile;
        }
        catch (Exception e) {
            throw new IllegalStateException("Unable to import control profile", e);
        }
    }

    public boolean hasVisibleProfileNamed(String name, int excludingId) {
        if (!profilesLoaded) loadProfiles();
        for (ControlsProfile profile : profiles) {
            if (profile.id != excludingId && profile.isListed() && profile.getName().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public static String normalizeProfileName(String value) {
        String normalized = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (normalized.length() > MAX_PROFILE_NAME_LENGTH) {
            normalized = normalized.substring(0, MAX_PROFILE_NAME_LENGTH).trim();
        }
        if (normalized.isEmpty()) throw new IllegalArgumentException("Control profile name cannot be empty");
        return normalized;
    }

    public static ControlsProfile loadProfile(Context context, File file) {
        try {
            return loadProfile(context, new FileInputStream(file));
        }
        catch (FileNotFoundException e) {
            return null;
        }
    }

    public static ControlsProfile loadProfile(Context context, InputStream inStream) {
        try (JsonReader reader = new JsonReader(new InputStreamReader(inStream, StandardCharsets.UTF_8))) {
            int profileId = 0;
            boolean hasProfileId = false;
            String profileName = null;
            float cursorSpeed = ControlsProfile.DEFAULT_CURSOR_SPEED;
            boolean listed = true;
            int libraryProfileId = -1;
            int maxReferencedProfileId = -1;
            String gameOwnerId = "";

            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();

                if (name.equals("id")) {
                    profileId = reader.nextInt();
                    hasProfileId = true;
                }
                else if (name.equals("name")) {
                    profileName = reader.nextString();
                }
                else if (name.equals("cursorSpeed")) {
                    cursorSpeed = (float) reader.nextDouble();
                }
                else if (name.equals("listed")) {
                    listed = reader.nextBoolean();
                }
                else if (name.equals("libraryProfileId")) {
                    libraryProfileId = reader.nextInt();
                    maxReferencedProfileId = Math.max(maxReferencedProfileId, libraryProfileId);
                }
                else if (name.equals("sectionSources")) {
                    reader.beginObject();
                    while (reader.hasNext()) {
                        reader.nextName();
                        maxReferencedProfileId = Math.max(maxReferencedProfileId, reader.nextInt());
                    }
                    reader.endObject();
                }
                else if (name.equals("gameOwnerId")) {
                    gameOwnerId = reader.nextString();
                }
                else {
                    reader.skipValue();
                }
            }
            reader.endObject();

            if (!hasProfileId || profileId < 0 || profileId > MAX_PROFILE_ID || maxReferencedProfileId > MAX_PROFILE_ID || profileName == null ||
                    profileName.trim().isEmpty() || !Float.isFinite(cursorSpeed)) {
                return null;
            }

            ControlsProfile profile = new ControlsProfile(context, profileId);
            profile.setName(normalizeProfileName(profileName));
            profile.setCursorSpeed(cursorSpeed);
            profile.setListed(listed);
            profile.setLibraryProfileId(libraryProfileId);
            profile.setMaxReferencedProfileId(maxReferencedProfileId);
            profile.setGameOwnerId(gameOwnerId);
            return profile;
        }
        catch (Exception e) {
            Log.w("InputControlsManager", "Ignoring malformed control profile", e);
            return null;
        }
    }

    public ControlsProfile getProfile(int id) {
        if (!profilesLoaded) loadProfiles();
        for (ControlsProfile profile : profiles) if (profile.id == id) return profile;
        return null;
    }

    private ControlsProfile loadInstalledProfile(File file) {
        Matcher matcher = PROFILE_FILE_PATTERN.matcher(file.getName());
        if (!file.isFile() || !matcher.matches()) return null;
        int fileId;
        try {
            fileId = Integer.parseInt(matcher.group(1));
        }
        catch (NumberFormatException e) {
            return null;
        }
        ControlsProfile profile = loadProfile(context, file);
        if (profile != null && profile.id != fileId) {
            Log.w("InputControlsManager", "Ignoring control profile whose ID does not match its filename: " + file);
            return null;
        }
        return profile;
    }
}
