package com.winlator.core;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import app.gamenative.MainActivity;
import app.gamenative.R;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.core.TarCompressorUtils;
import com.winlator.xenvironment.ImageFs;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

public abstract class GeneralComponents {

    public enum Type {
        BOX64,
        TURNIP,
        DXVK,
        VKD3D,
        WINED3D,
        SOUNDFONT,
        ADRENOTOOLS_DRIVER;

        public String lowerName() {
            return name().toLowerCase(Locale.ENGLISH);
        }

        public String title() {
            switch (this) {
                case BOX64:
                    return "Box64";
                case TURNIP:
                    return "Turnip";
                case DXVK:
                    return "DXVK";
                case VKD3D:
                    return "VKD3D";
                case WINED3D:
                    return "WineD3D";
                case SOUNDFONT:
                    return "SoundFont";
                case ADRENOTOOLS_DRIVER:
                    return "Adrenotools Driver";
                default:
                    return "";
            }
        }

        private String assetFolder() {
            switch (this) {
                case BOX64:
                    return "box64";
                case TURNIP:
                    return "graphics_driver";
                case DXVK:
                case VKD3D:
                case WINED3D:
                    return "dxwrapper";
                case SOUNDFONT:
                    return "soundfont";
                case ADRENOTOOLS_DRIVER:
                default:
                    return "";
            }
        }

        private File getSource(Context context, String identifier) {
            File componentDir = GeneralComponents.getComponentDir(this, context);
            switch (this) {
                case SOUNDFONT:
                    return new File(componentDir, identifier + ".sf2");
                case ADRENOTOOLS_DRIVER:
                    return new File(componentDir, identifier);
                default:
                    return new File(componentDir, lowerName() + "-" + identifier + ".tzst");
            }
        }

        public File getDestination(Context context) {
            File rootDir = ImageFs.find(context).getRootDir();
            switch (this) {
                case DXVK:
                case VKD3D:
                case WINED3D:
                    return new File(rootDir, "/home/xuser/.wine/drive_c/windows");
                case SOUNDFONT:
                    File destination = new File(context.getCacheDir(), "soundfont");
                    if (!destination.isDirectory()) {
                        destination.mkdirs();
                    }
                    return destination;
                default:
                    return rootDir;
            }
        }

        private int getInstallModes() {
            if (this == SOUNDFONT || this == ADRENOTOOLS_DRIVER) {
                return 2; // File install mode
            }
            return 1; // Download install mode
        }

        private boolean isVersioned() {
            return this == BOX64 || this == TURNIP || this == DXVK || this == VKD3D || this == WINED3D;
        }
    }

    public static ArrayList<String> getBuiltinComponentNames(Type type) {
        String[] items = new String[0];
        switch (type) {
            case BOX64:
                items = new String[]{"0.3.4", "0.3.6"};
                break;
            case TURNIP:
                items = new String[]{"25.1.0"};
                break;
            case DXVK:
                items = new String[]{"1.10.3", "2.4.1"};
                break;
            case VKD3D:
                items = new String[]{"2.13"};
                break;
            case WINED3D:
                items = new String[]{"9.2"};
                break;
            case SOUNDFONT:
                items = new String[]{"SONiVOX-EAS-GM-Wavetable"};
                break;
            case ADRENOTOOLS_DRIVER:
                items = new String[]{"System"};
                break;
        }
        return new ArrayList<>(Arrays.asList(items));
    }

    public static File getComponentDir(Type type, Context context) {
        File file = new File(context.getFilesDir(), "/installed_components/" + type.lowerName());
        if (!file.isDirectory()) {
            file.mkdirs();
        }
        return file;
    }

    public static boolean isBuiltinComponent(Type type, String identifier) {
        for (String builtinComponentName : getBuiltinComponentNames(type)) {
            if (builtinComponentName.equalsIgnoreCase(identifier)) {
                return true;
            }
        }
        return false;
    }

    public static String getDefinitivePath(Type type, Context context, String identifier) {
        if (identifier.isEmpty()) {
            return null;
        }

        if (type == Type.SOUNDFONT && isBuiltinComponent(type, identifier)) {
            File destination = type.getDestination(context);
            FileUtils.clear(destination);
            String filename = identifier + ".sf2";
            File destinationFile = new File(destination, filename);
            FileUtils.copy(context, type.assetFolder() + "/" + filename, destinationFile);
            return destinationFile.getPath();
        }

        if (type == Type.ADRENOTOOLS_DRIVER) {
            if (isBuiltinComponent(type, identifier)) {
                return null;
            }
            File source = type.getSource(context, identifier);
            File[] manifestFiles = source.listFiles((file, name) -> name.endsWith(".json"));
            if (manifestFiles != null && manifestFiles.length > 0) {
                try {
                    JSONObject manifestJSONObject = new JSONObject(FileUtils.readString(manifestFiles[0]));
                    String libraryName = manifestJSONObject.optString("libraryName", "");
                    File libraryFile = new File(source, libraryName);
                    if (libraryFile.isFile()) {
                        return libraryFile.getPath();
                    }
                    return null;
                } catch (JSONException e) {
                    return null;
                }
            }
        }
        return type.getSource(context, identifier).getPath();
    }
}
