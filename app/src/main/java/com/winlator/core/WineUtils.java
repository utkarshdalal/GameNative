package com.winlator.core;

import android.content.Context;
import android.util.Log;

import com.winlator.container.Container;
import com.winlator.xenvironment.ImageFs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Iterator;
import java.util.Locale;

import timber.log.Timber;

public abstract class WineUtils {
    public static void createDosdevicesSymlinks(Container container) {
        String dosdevicesPath = (new File(container.getRootDir(), ".wine/dosdevices")).getPath();
        File[] files = (new File(dosdevicesPath)).listFiles();
        if (files != null) for (File file : files) if (file.getName().matches("[a-z]:")) file.delete();

        FileUtils.symlink("../drive_c", dosdevicesPath+"/c:");
        FileUtils.symlink(container.getRootDir().getPath() + "/../..", dosdevicesPath+"/z:");

        String gameDirectoryPath = null;
        for (String[] drive : container.drivesIterator()) {
            File linkTarget = new File(drive[1]);
            String path = linkTarget.getAbsolutePath();
            if (!linkTarget.isDirectory() && path.endsWith("/app.gamenative/storage")) {
                linkTarget.mkdirs();
                FileUtils.chmod(linkTarget, 0771);
            }
            FileUtils.symlink(path, dosdevicesPath+"/"+drive[0].toLowerCase(Locale.ENGLISH)+":");

            // Check if this is the A: drive (game directory)
            if (drive[0].equals("A") && path.contains("/Steam/steamapps/common/")) {
                gameDirectoryPath = path;
            }
        }

        // Create Steam symlink if we found the game directory
        if (gameDirectoryPath != null) {
            // Extract game name from path like "/data/data/app.gamenative/Steam/steamapps/common/GameName"
            String gameName = new File(gameDirectoryPath).getName();

            // Create the Steam directory structure in C: drive
            File steamCommonDir = new File(container.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam/steamapps/common");
            if (!steamCommonDir.exists()) {
                steamCommonDir.mkdirs();
            }

            // Create symlink from C:\Program Files (x86)\Steam\steamapps\common\{gameName} to actual game directory
            File steamGameLink = new File(steamCommonDir, gameName);
            if (!steamGameLink.exists()) {
                FileUtils.symlink(gameDirectoryPath, steamGameLink.getAbsolutePath());
            }

            // Create the steamapps folder and ACF manifest
            File steamappsDir = new File(container.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam/steamapps");
            if (!steamappsDir.exists()) {
                steamappsDir.mkdirs();
            }
        }
    }

    private static void setWindowMetrics(WineRegistryEditor registryEditor) {
        byte[] fontNormalData = (new MSLogFont()).toByteArray();
        byte[] fontBoldData = (new MSLogFont()).setWeight(700).toByteArray();
        registryEditor.setHexValue("Control Panel\\Desktop\\WindowMetrics", "CaptionFont", fontBoldData);
        registryEditor.setHexValue("Control Panel\\Desktop\\WindowMetrics", "IconFont", fontNormalData);
        registryEditor.setHexValue("Control Panel\\Desktop\\WindowMetrics", "MenuFont", fontNormalData);
        registryEditor.setHexValue("Control Panel\\Desktop\\WindowMetrics", "MessageFont", fontNormalData);
        registryEditor.setHexValue("Control Panel\\Desktop\\WindowMetrics", "SmCaptionFont", fontNormalData);
        registryEditor.setHexValue("Control Panel\\Desktop\\WindowMetrics", "StatusFont", fontNormalData);
    }

    public static void applySystemTweaks(Context context, WineInfo wineInfo) {
        File rootDir = ImageFs.find(context).getRootDir();
        File systemRegFile = new File(rootDir, ImageFs.WINEPREFIX+"/system.reg");
        File userRegFile = new File(rootDir, ImageFs.WINEPREFIX+"/user.reg");
        File userCacheDir = new File(rootDir, "/home/xuser/.cache");
        if (!userCacheDir.isDirectory()) {
            userCacheDir.mkdirs();
        }
        File userConfigDir = new File(rootDir, "/home/xuser/.config");
        if (!userConfigDir.isDirectory()) {
            userConfigDir.mkdirs();
        }

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
            registryEditor.setStringValue("Software\\Classes\\.reg", null, "REGfile");
            registryEditor.setStringValue("Software\\Classes\\.reg", "Content Type", "application/reg");
            registryEditor.setStringValue("Software\\Classes\\REGfile\\Shell\\Open\\command", null, "C:\\windows\\regedit.exe /C \"%1\"");

            registryEditor.setStringValue("Software\\Classes\\dllfile\\DefaultIcon", null, "shell32.dll,-154");
            registryEditor.setStringValue("Software\\Classes\\lnkfile\\DefaultIcon", null, "shell32.dll,-30");
            registryEditor.setStringValue("Software\\Classes\\inifile\\DefaultIcon", null, "shell32.dll,-151");

            File corefontsAddedFile = new File(userConfigDir, "corefonts.added");
            if (!corefontsAddedFile.isFile()) {
                try {
                    setupSystemFonts(registryEditor);
                    FileUtils.writeString(corefontsAddedFile, String.valueOf(System.currentTimeMillis()));
                } catch (Throwable th) {
                    registryEditor.close();
                }
            }
        }

        final String[] direct3dLibs = {"d3d8", "d3d9", "d3d10", "d3d10_1", "d3d10core", "d3d11", "d3d12", "d3d12core", "ddraw", "dxgi", "wined3d"};
        final String[] xinputLibs = {"dinput", "dinput8", "xinput1_1", "xinput1_2", "xinput1_3", "xinput1_4", "xinput9_1_0", "xinputuap"};
        final String dllOverridesKey = "Software\\Wine\\DllOverrides";

        boolean isMainWineVersion = WineInfo.isMainWineVersion(wineInfo.identifier());

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            for (String name : direct3dLibs) registryEditor.setStringValue(dllOverridesKey, name, "native,builtin");
            for (String name : xinputLibs) registryEditor.setStringValue(dllOverridesKey, name, "builtin,native");

            registryEditor.removeKey("Software\\Winlator\\WFM\\ContextMenu\\7-Zip");
            registryEditor.setStringValue("Software\\Winlator\\WFM\\ContextMenu\\7-Zip", "Open Archive", "Z:\\opt\\apps\\7-Zip\\7zFM.exe \"%FILE%\"");
            registryEditor.setStringValue("Software\\Winlator\\WFM\\ContextMenu\\7-Zip", "Extract Here", "Z:\\opt\\apps\\7-Zip\\7zG.exe x \"%FILE%\" -r -o\"%DIR%\" -y");
            registryEditor.setStringValue("Software\\Winlator\\WFM\\ContextMenu\\7-Zip", "Extract to Folder", "Z:\\opt\\apps\\7-Zip\\7zG.exe x \"%FILE%\" -r -o\"%DIR%\\%BASENAME%\" -y");

            setWindowMetrics(registryEditor);
        }

        File wineSystem32Dir = new File(rootDir, "/opt/wine/lib/wine/x86_64-windows");
        File wineSysWoW64Dir = new File(rootDir, "/opt/wine/lib/wine/i386-windows");
        File containerSystem32Dir = new File(rootDir, ImageFs.WINEPREFIX+"/drive_c/windows/system32");
        File containerSysWoW64Dir = new File(rootDir, ImageFs.WINEPREFIX+"/drive_c/windows/syswow64");

        final String[] dlnames = {"user32.dll", "shell32.dll", "dinput.dll", "dinput8.dll", "xinput1_1.dll", "xinput1_2.dll", "xinput1_3.dll", "xinput1_4.dll", "xinput9_1_0.dll", "xinputuap.dll", "winemenubuilder.exe", "explorer.exe"};
        boolean win64 = wineInfo.isWin64();
        for (String dlname : dlnames) {
            FileUtils.copy(new File(wineSysWoW64Dir, dlname), new File(win64 ? containerSysWoW64Dir : containerSystem32Dir, dlname));
            if (win64) FileUtils.copy(new File(wineSystem32Dir, dlname), new File(containerSystem32Dir, dlname));
        }
    }

    public static void overrideWinComponentDlls(Context context, Container container, String wincomponents) {
        final String dllOverridesKey = "Software\\Wine\\DllOverrides";
        File userRegFile = new File(container.getRootDir(), ".wine/user.reg");
        Iterator<String[]> oldWinComponentsIter = new KeyValueSet(container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS)).iterator();

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            JSONObject wincomponentsJSONObject = new JSONObject(FileUtils.readString(context, "wincomponents/wincomponents.json"));

            for (String[] wincomponent : new KeyValueSet(wincomponents)) {
                if (wincomponent[1].equals(oldWinComponentsIter.next()[1])) continue;
                String identifier = wincomponent[0];
                boolean useNative = wincomponent[1].equals("1");

                JSONArray dlnames = wincomponentsJSONObject.getJSONArray(identifier);
                for (int i = 0; i < dlnames.length(); i++) {
                    String dlname = dlnames.getString(i);
                    if (useNative) {
                        registryEditor.setStringValue(dllOverridesKey, dlname, "native,builtin");
                    }
                    else registryEditor.removeValue(dllOverridesKey, dlname);
                }
            }
        }
        catch (JSONException e) {
            Log.e("WineUtils", "Failed to override win component dlls: " + e);
        }
    }

    public static void setWinComponentRegistryKeys(File systemRegFile, String identifier, boolean useNative) {
        if (identifier.equals("directsound")) {
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
                final String key64 = "Software\\Classes\\CLSID\\{083863F1-70DE-11D0-BD40-00A0C911CE86}\\Instance\\{E30629D1-27E5-11CE-875D-00608CB78066}";
                final String key32 = "Software\\Classes\\Wow6432Node\\CLSID\\{083863F1-70DE-11D0-BD40-00A0C911CE86}\\Instance\\{E30629D1-27E5-11CE-875D-00608CB78066}";

                if (useNative) {
                    registryEditor.setStringValue(key32, "CLSID", "{E30629D1-27E5-11CE-875D-00608CB78066}");
                    registryEditor.setHexValue(key32, "FilterData", "02000000000080000100000000000000307069330200000000000000010000000000000000000000307479330000000038000000480000006175647300001000800000aa00389b710100000000001000800000aa00389b71");
                    registryEditor.setStringValue(key32, "FriendlyName", "Wave Audio Renderer");

                    registryEditor.setStringValue(key64, "CLSID", "{E30629D1-27E5-11CE-875D-00608CB78066}");
                    registryEditor.setHexValue(key64, "FilterData", "02000000000080000100000000000000307069330200000000000000010000000000000000000000307479330000000038000000480000006175647300001000800000aa00389b710100000000001000800000aa00389b71");
                    registryEditor.setStringValue(key64, "FriendlyName", "Wave Audio Renderer");
                }
                else {
                    registryEditor.removeKey(key32);
                    registryEditor.removeKey(key64);
                }
            }
        }
        else if (identifier.equals("wmdecoder")) {
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
                if (useNative) {
                    registryEditor.setStringValue("Software\\Classes\\Wow6432Node\\CLSID\\{2EEB4ADF-4578-4D10-BCA7-BB955F56320A}\\InprocServer32", null, "C:\\windows\\system32\\wmadmod.dll");
                    registryEditor.setStringValue("Software\\Classes\\Wow6432Node\\CLSID\\{82D353DF-90BD-4382-8BC2-3F6192B76E34}\\InprocServer32", null, "C:\\windows\\system32\\wmvdecod.dll");
                }
                else {
                    registryEditor.setStringValue("Software\\Classes\\Wow6432Node\\CLSID\\{2EEB4ADF-4578-4D10-BCA7-BB955F56320A}\\InprocServer32", null, "C:\\windows\\system32\\winegstreamer.dll");
                    registryEditor.setStringValue("Software\\Classes\\Wow6432Node\\CLSID\\{82D353DF-90BD-4382-8BC2-3F6192B76E34}\\InprocServer32", null, "C:\\windows\\system32\\winegstreamer.dll");
                }
            }
        }
    }

    public static void changeServicesStatus(Container container, boolean onlyEssential) {
        final String[] services = {"BITS:3", "Eventlog:2", "HTTP:3", "LanmanServer:3", "NDIS:2", "PlugPlay:2", "RpcSs:3", "scardsvr:3", "Schedule:3", "Spooler:3", "StiSvc:3", "TermService:3", "winebus:3", "winehid:3", "Winmgmt:3", "wuauserv:3"};
        File systemRegFile = new File(container.getRootDir(), ".wine/system.reg");

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
            registryEditor.setCreateKeyIfNotExist(false);

            for (String service : services) {
                String name = service.substring(0, service.indexOf(":"));
                int value = onlyEssential ? 4 : Character.getNumericValue(service.charAt(service.length()-1));
                registryEditor.setDwordValue("System\\CurrentControlSet\\Services\\"+name, "Start", value);
            }
        }
    }

    private static void setupSystemFonts(WineRegistryEditor registryEditor) {
        Timber.i("Setting up fonts!");
        String[][] corefonts = {new String[]{"Andale Mono (TrueType)", "andalemo.ttf"}, new String[]{"Arial (TrueType)", "arial.ttf"}, new String[]{"Arial Black (TrueType)", "ariblk.ttf"}, new String[]{"Arial Bold (TrueType)", "arialbd.ttf"}, new String[]{"Arial Bold Italic (TrueType)", "arialbi.ttf"}, new String[]{"Arial Italic (TrueType)", "ariali.ttf"}, new String[]{"Comic Sans MS (TrueType)", "comic.ttf"}, new String[]{"Comic Sans MS Bold (TrueType)", "comicbd.ttf"}, new String[]{"Courier New (TrueType)", "cour.ttf"}, new String[]{"Courier New Bold (TrueType)", "courbd.ttf"}, new String[]{"Courier New Bold Italic (TrueType)", "courbi.ttf"}, new String[]{"Courier New Italic (TrueType)", "couri.ttf"}, new String[]{"Georgia (TrueType)", "georgia.ttf"}, new String[]{"Georgia Bold (TrueType)", "georgiab.ttf"}, new String[]{"Georgia Bold Italic (TrueType)", "georgiaz.ttf"}, new String[]{"Georgia Italic (TrueType)", "georgiai.ttf"}, new String[]{"Impact (TrueType)", "impact.ttf"}, new String[]{"Times New Roman (TrueType)", "times.ttf"}, new String[]{"Times New Roman Bold (TrueType)", "timesbd.ttf"}, new String[]{"Times New Roman Bold Italic (TrueType)", "timesbi.ttf"}, new String[]{"Times New Roman Italic (TrueType)", "timesi.ttf"}, new String[]{"Trebuchet MS (TrueType)", "trebuc.ttf"}, new String[]{"Trebuchet MS Bold (TrueType)", "trebucbd.ttf"}, new String[]{"Trebuchet MS Bold Italic (TrueType)", "trebucbi.ttf"}, new String[]{"Trebuchet MS Italic (TrueType)", "trebucit.ttf"}, new String[]{"Verdana (TrueType)", "verdana.ttf"}, new String[]{"Verdana Bold (TrueType)", "verdanab.ttf"}, new String[]{"Verdana Bold Italic (TrueType)", "verdanaz.ttf"}, new String[]{"Verdana Italic (TrueType)", "verdanai.ttf"}, new String[]{"Webdings (TrueType)", "webdings.ttf"}};
        registryEditor.setStringValues("Software\\Microsoft\\Windows\\CurrentVersion\\Fonts", corefonts);
        registryEditor.setStringValues("Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts", corefonts);
        Timber.i("Setting up corefonts! " + corefonts);
        String[][] wineFonts = {new String[]{"Marlett (TrueType)", "Z:\\opt\\wine\\share\\wine\\fonts\\marlett.ttf"}, new String[]{"Symbol (TrueType)", "Z:\\opt\\wine\\share\\wine\\fonts\\symbol.ttf"}, new String[]{"Tahoma (TrueType)", "Z:\\opt\\wine\\share\\wine\\fonts\\tahoma.ttf"}, new String[]{"Tahoma Bold (TrueType)", "Z:\\opt\\wine\\share\\wine\\fonts\\tahomabd.ttf"}, new String[]{"Wingdings (TrueType)", "Z:\\opt\\wine\\share\\wine\\fonts\\wingding.ttf"}};
        registryEditor.setStringValues("Software\\Microsoft\\Windows\\CurrentVersion\\Fonts", wineFonts);
        registryEditor.setStringValues("Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts", wineFonts);
        Timber.i("Setting up winefonts! " + wineFonts);
    }
}
