package com.winlator.inputcontrols;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.JsonReader;
import android.util.Log;

import com.winlator.PrefManager;
import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;

import org.json.JSONException;
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

public class InputControlsManager {
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
        if (!profilesLoaded) loadProfiles(false);
        ArrayList<ControlsProfile> visibleProfiles = new ArrayList<>();
        for (ControlsProfile profile : profiles) {
            if (!profile.isListed()) continue;
            if (ignoreTemplates && profile.isTemplate()) continue;
            visibleProfiles.add(profile);
        }
        return visibleProfiles;
    }

    private void copyAssetProfilesIfNeeded() {
        File profilesDir = InputControlsManager.getProfilesDir(context);
        if (FileUtils.isEmpty(profilesDir)) {
            FileUtils.copy(context, "inputcontrols/profiles", profilesDir);
            return;
        }

        PrefManager.init(context);
        String newVersion = String.valueOf(AppUtils.getVersionCode(context));
        String oldVersion = PrefManager.getString("inputcontrols_app_version", "0");
        if (oldVersion.equals(newVersion)) return;
        PrefManager.putString("inputcontrols_app_version", newVersion);

        File[] files = profilesDir.listFiles();
        if (files == null) return;

        try {
            AssetManager assetManager = context.getAssets();
            String[] assetFiles = assetManager.list("inputcontrols/profiles");

            // Pre-compute max local ID for assigning new profile IDs
            int nextNewId = 0;
            for (File f : files) {
                ControlsProfile p = loadProfile(context, f);
                if (p != null) nextNewId = Math.max(nextNewId, p.id + 1);
            }

            for (String assetFile : assetFiles) {
                String assetPath = "inputcontrols/profiles/"+assetFile;
                ControlsProfile originProfile = loadProfile(context, assetManager.open(assetPath));

                File targetFile = null;
                for (File file : files) {
                    ControlsProfile targetProfile = loadProfile(context, file);
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
                            ControlsProfile p = loadProfile(context, file2);
                            if (p != null && p.getName().equals(originProfile.getName())) {
                                alreadyExists = true;
                                break;
                            }
                        }
                        if (!alreadyExists) {
                            int newId = nextNewId++;
                            File freeFile = ControlsProfile.getProfileFile(context, newId);
                            try {
                                String json = FileUtils.readString(context, assetPath);
                                JSONObject data = new JSONObject(json);
                                data.put("id", newId);
                                FileUtils.writeString(freeFile, data.toString());
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

    public void loadProfiles(boolean ignoreTemplates) {
        File profilesDir = InputControlsManager.getProfilesDir(context);
        copyAssetProfilesIfNeeded();

        ArrayList<ControlsProfile> profiles = new ArrayList<>();
        File[] files = profilesDir.listFiles();
        if (files != null) {
            for (File file : files) {
                ControlsProfile profile = loadProfile(context, file);
                if (profile == null) continue;
                profiles.add(profile);
                maxProfileId = Math.max(maxProfileId, profile.id);
            }
        }

        Collections.sort(profiles);
        this.profiles = profiles;
        profilesLoaded = true;
    }

    public void reloadProfiles() {
        profilesLoaded = false;
        maxProfileId = 0;
        loadProfiles(false);
    }

    public int nextProfileId() {
        if (!profilesLoaded) loadProfiles(false);
        return ++maxProfileId;
    }

    public ControlsProfile createProfile(String name) {
        if (!profilesLoaded) loadProfiles(false);
        ControlsProfile profile = new ControlsProfile(context, ++maxProfileId);
        profile.setName(name);
        profile.save();
        profiles.add(profile);
        return profile;
    }

    public ControlsProfile duplicateProfile(ControlsProfile source) {
        if (!profilesLoaded) loadProfiles(false);
        String newName;
        for (int i = 1;;i++) {
            newName = source.getName() + " ("+i+")";
            boolean found = false;
            for (ControlsProfile profile : profiles) {
                if (profile.getName().equals(newName)) {
                    found = true;
                    break;
                }
            }
            if (!found) break;
        }

        int newId = ++maxProfileId;
        File newFile = ControlsProfile.getProfileFile(context, newId);

        try {
            JSONObject data = new JSONObject(FileUtils.readString(ControlsProfile.getProfileFile(context, source.id)));
            data.put("id", newId);
            data.put("name", newName);
            if (data.has("template")) data.remove("template");
            data.put("listed", true);
            data.remove("libraryProfileId");
            data.remove("gameOwnerId");
            FileUtils.writeString(newFile, data.toString());
        }
        catch (JSONException e) {}

        ControlsProfile profile = loadProfile(context, newFile);
        profiles.add(profile);
        return profile;
    }

    public void removeProfile(ControlsProfile profile) {
        File file = ControlsProfile.getProfileFile(context, profile.id);
        if (file.isFile() && file.delete()) profiles.remove(profile);
    }

    public ControlsProfile importProfile(JSONObject data) {
        try {
            if (!data.has("name")) return null;
            if (!profilesLoaded) loadProfiles(false);
            int newId = ++maxProfileId;
            File newFile = ControlsProfile.getProfileFile(context, newId);
            data.put("id", newId);
            data.put("listed", true);
            data.remove("libraryProfileId");
            data.remove("gameOwnerId");

            String baseName = data.optString("name", "Imported Profile").trim();
            if (baseName.isEmpty()) baseName = "Imported Profile";
            String uniqueName = baseName;
            for (int suffix = 1; hasVisibleProfileNamed(uniqueName); suffix++) {
                uniqueName = baseName + " (" + suffix + ")";
            }
            data.put("name", uniqueName);
            FileUtils.writeString(newFile, data.toString());
            ControlsProfile newProfile = loadProfile(context, newFile);
            if (newProfile != null) profiles.add(newProfile);
            return newProfile;
        }
        catch (JSONException e) {
            return null;
        }
    }

    private boolean hasVisibleProfileNamed(String name) {
        for (ControlsProfile profile : profiles) {
            if (profile.isListed() && profile.getName().equalsIgnoreCase(name)) return true;
        }
        return false;
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
            String profileName = null;
            float cursorSpeed = ControlsProfile.DEFAULT_CURSOR_SPEED;
            boolean listed = true;
            int libraryProfileId = -1;
            String gameOwnerId = "";

            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();

                if (name.equals("id")) {
                    profileId = reader.nextInt();
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
                }
                else if (name.equals("gameOwnerId")) {
                    gameOwnerId = reader.nextString();
                }
                else {
                    reader.skipValue();
                }
            }

            ControlsProfile profile = new ControlsProfile(context, profileId);
            profile.setName(profileName);
            profile.setCursorSpeed(cursorSpeed);
            profile.setListed(listed);
            profile.setLibraryProfileId(libraryProfileId);
            profile.setGameOwnerId(gameOwnerId);
            return profile;
        }
        catch (IOException e) {
            return null;
        }
    }

    public ControlsProfile getProfile(int id) {
        if (!profilesLoaded) loadProfiles(false);
        for (ControlsProfile profile : profiles) if (profile.id == id) return profile;
        return null;
    }
}
