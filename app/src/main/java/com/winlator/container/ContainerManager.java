package com.winlator.container;

import static com.winlator.container.Container.STEAM_TYPE_LIGHT;
import static com.winlator.container.Container.STEAM_TYPE_NORMAL;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

// import com.winlator.R;
import app.gamenative.R;
import com.winlator.box86_64.Box86_64Preset;
import com.winlator.core.Callback;
import com.winlator.core.FileUtils;
import com.winlator.core.OnExtractFileListener;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.WineInfo;
import com.winlator.core.WineThemeManager;
import com.winlator.xenvironment.ImageFs;
import com.winlator.core.GPUInformation;
import com.winlator.core.DefaultVersion;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ContainerManager {
    private final ArrayList<Container> containers = new ArrayList<>();
    private final File homeDir;
    private final Context context;

    public ContainerManager(Context context) {
        this.context = context;
        // Override default driver and DXVK version based on Turnip capability
        if (GPUInformation.isTurnipCapable(context)) {
            Container.DEFAULT_GRAPHICS_DRIVER = "turnip";
            DefaultVersion.DXVK = "2.6.1-gplasync";
            DefaultVersion.VKD3D = "2.14.1";
            DefaultVersion.STEAM_TYPE = STEAM_TYPE_NORMAL;
        } else {
            Container.DEFAULT_GRAPHICS_DRIVER = "vortek";
            DefaultVersion.DXVK = "1.10.9-sarek";
            DefaultVersion.VKD3D = "2.6";
            DefaultVersion.STEAM_TYPE = STEAM_TYPE_LIGHT;
        }
        File rootDir = ImageFs.find(context).getRootDir();
        homeDir = new File(rootDir, "home");
        loadContainers();
    }

    public ArrayList<Container> getContainers() {
        return containers;
    }

    private void loadContainers() {
        containers.clear();

        try {
            File[] files = homeDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        if (file.getName().startsWith(ImageFs.USER+"-")) {
                            String containerId = file.getName().replace(ImageFs.USER+"-", "");
                            Container container = new Container(containerId);
                            container.setRootDir(new File(homeDir, ImageFs.USER+"-"+container.id));
                            JSONObject data = new JSONObject(FileUtils.readString(container.getConfigFile()));
                            container.loadData(data);
                            containers.add(container);
                        }
                    }
                }
            }
        }
        catch (JSONException e) {
            Log.e("ContainerManager", "Failed to load containers: " + e);
        }
    }

    public void activateContainer(Container container) {
        container.setRootDir(new File(homeDir, ImageFs.USER+"-"+container.id));
        File file = new File(homeDir, ImageFs.USER);
        file.delete();
        FileUtils.symlink("./"+ImageFs.USER+"-"+container.id, file.getPath());
    }

    public void createContainerAsync(String containerId, final JSONObject data, Callback<Container> callback) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            final Container container = createContainer(containerId, data);
            handler.post(() -> callback.call(container));
        });
    }
    public Future<Container> createContainerFuture(String containerId, final JSONObject data) {
        return Executors.newSingleThreadExecutor().submit(() -> createContainer(containerId, data));
    }
    public Future<Container> createDefaultContainerFuture(WineInfo wineInfo, String containerId) {
        String name = "container_" + containerId;
        Log.d("XServerScreen", "Creating container $name");
        String screenSize = Container.DEFAULT_SCREEN_SIZE;
        String envVars = Container.DEFAULT_ENV_VARS;
        String graphicsDriver = Container.DEFAULT_GRAPHICS_DRIVER;
        String dxwrapper = Container.DEFAULT_DXWRAPPER;
        String dxwrapperConfig = "";
        String audioDriver = Container.DEFAULT_AUDIO_DRIVER;
        String wincomponents = Container.DEFAULT_WINCOMPONENTS;
        String drives = "";
        Boolean showFPS = false;
        String cpuList = Container.getFallbackCPUList();
        String cpuListWoW64 = Container.getFallbackCPUListWoW64();
        Boolean wow64Mode = WineInfo.isMainWineVersion(wineInfo.identifier());
        // Boolean wow64Mode = false;
        Byte startupSelection = Container.STARTUP_SELECTION_ESSENTIAL;
        String box86Preset = Box86_64Preset.COMPATIBILITY;
        String box64Preset = Box86_64Preset.COMPATIBILITY;
        String desktopTheme = WineThemeManager.DEFAULT_DESKTOP_THEME;

        JSONObject data = new JSONObject();
        try {
            data.put("name", name);
            data.put("screenSize", screenSize);
            data.put("envVars", envVars);
            data.put("cpuList", cpuList);
            data.put("cpuListWoW64", cpuListWoW64);
            data.put("graphicsDriver", graphicsDriver);
            data.put("dxwrapper", dxwrapper);
            data.put("dxwrapperConfig", dxwrapperConfig);
            data.put("audioDriver", audioDriver);
            data.put("wincomponents", wincomponents);
            data.put("drives", drives);
            data.put("showFPS", showFPS);
            data.put("wow64Mode", wow64Mode);
            data.put("startupSelection", startupSelection);
            data.put("box86Preset", box86Preset);
            data.put("box64Preset", box64Preset);
            data.put("desktopTheme", desktopTheme);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }

        return createContainerFuture(containerId, data);
    }

    public void duplicateContainerAsync(Container container, Runnable callback) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            duplicateContainer(container);
            handler.post(callback);
        });
    }

    public void removeContainerAsync(Container container, Runnable callback) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            removeContainer(container);
            handler.post(callback);
        });
    }

    public Container createContainer(String containerId, JSONObject data) {
        try {
            data.put("id", containerId);

            File containerDir = new File(homeDir, ImageFs.USER+"-"+containerId);
            if (!containerDir.mkdirs()) return null;

            Container container = new Container(containerId);
            container.setRootDir(containerDir);
            container.loadData(data);

            boolean isMainWineVersion = !data.has("wineVersion") || WineInfo.isMainWineVersion(data.getString("wineVersion"));
            if (!isMainWineVersion) container.setWineVersion(data.getString("wineVersion"));

            if (!extractContainerPatternFile(container.getWineVersion(), containerDir, null)) {
                FileUtils.delete(containerDir);
                return null;
            }

            container.saveData();
            containers.add(container);
            return container;
        }
        catch (JSONException e) {
            Log.e("ContainerManager", "Failed to create container: " + e);
        }
        return null;
    }

    private void duplicateContainer(Container srcContainer) {
        // Generate a unique ID by appending (1), (2), etc. to the original ID
        String baseId = srcContainer.id;
        String newId = generateUniqueContainerId(baseId);

        File dstDir = new File(homeDir, ImageFs.USER+"-"+newId);
        if (!dstDir.mkdirs()) return;

        if (!FileUtils.copy(srcContainer.getRootDir(), dstDir, (file) -> FileUtils.chmod(file, 0771))) {
            FileUtils.delete(dstDir);
            return;
        }

        Container dstContainer = new Container(newId);
        dstContainer.setRootDir(dstDir);
        dstContainer.setName(srcContainer.getName()+" ("+context.getString(R.string.copy)+")");
        dstContainer.setScreenSize(srcContainer.getScreenSize());
        dstContainer.setEnvVars(srcContainer.getEnvVars());
        dstContainer.setCPUList(srcContainer.getCPUList());
        dstContainer.setCPUListWoW64(srcContainer.getCPUListWoW64());
        dstContainer.setGraphicsDriver(srcContainer.getGraphicsDriver());
        dstContainer.setDXWrapper(srcContainer.getDXWrapper());
        dstContainer.setDXWrapperConfig(srcContainer.getDXWrapperConfig());
        dstContainer.setAudioDriver(srcContainer.getAudioDriver());
        dstContainer.setWinComponents(srcContainer.getWinComponents());
        dstContainer.setDrives(srcContainer.getDrives());
        dstContainer.setShowFPS(srcContainer.isShowFPS());
        dstContainer.setWoW64Mode(srcContainer.isWoW64Mode());
        dstContainer.setStartupSelection(srcContainer.getStartupSelection());
        dstContainer.setBox86Preset(srcContainer.getBox86Preset());
        dstContainer.setBox64Preset(srcContainer.getBox64Preset());
        dstContainer.setBox64Version(srcContainer.getBox64Version());
        dstContainer.setBox86Version(srcContainer.getBox86Version());
        dstContainer.setDesktopTheme(srcContainer.getDesktopTheme());
        dstContainer.setRcfileId(srcContainer.getRCFileId());
        dstContainer.setWineVersion(srcContainer.getWineVersion());
        dstContainer.saveData();

        containers.add(dstContainer);
    }

    private String generateUniqueContainerId(String baseId) {
        // If the base ID doesn't exist, use it as-is
        if (!hasContainer(baseId)) {
            return baseId;
        }

        // Try baseId(1), baseId(2), etc. until we find a unique one
        int counter = 1;
        String candidateId;
        do {
            candidateId = baseId + "(" + counter + ")";
            counter++;
        } while (hasContainer(candidateId));

        return candidateId;
    }

    private void removeContainer(Container container) {
        if (FileUtils.delete(container.getRootDir())) containers.remove(container);
    }

    public ArrayList<Shortcut> loadShortcuts() {
        ArrayList<Shortcut> shortcuts = new ArrayList<>();
        for (Container container : containers) {
            File desktopDir = container.getDesktopDir();
            File[] files = desktopDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.getName().endsWith(".desktop")) shortcuts.add(new Shortcut(container, file));
                }
            }
        }

        shortcuts.sort(Comparator.comparing(a -> a.name));
        return shortcuts;
    }

    public boolean hasContainer(String id) {
        for (Container container : containers) if (container.id.equals(id)) return true;
        return false;
    }
    
    public Container getContainerById(String id) {
        for (Container container : containers) if (container.id.equals(id)) return container;
        return null;
    }

    private void extractCommonDlls(String srcName, String dstName, JSONObject commonDlls, File containerDir, OnExtractFileListener onExtractFileListener) throws JSONException {
        File srcDir = new File(ImageFs.find(context).getRootDir(), "/opt/wine/lib/wine/"+srcName);
        JSONArray dlnames = commonDlls.getJSONArray(dstName);

        for (int i = 0; i < dlnames.length(); i++) {
            String dlname = dlnames.getString(i);
            File dstFile = new File(containerDir, ".wine/drive_c/windows/"+dstName+"/"+dlname);
            if (onExtractFileListener != null) {
                dstFile = onExtractFileListener.onExtractFile(dstFile, 0);
                if (dstFile == null) continue;
            }
            FileUtils.copy(new File(srcDir, dlname), dstFile);
        }
    }

    public boolean extractContainerPatternFile(String wineVersion, File containerDir, OnExtractFileListener onExtractFileListener) {
        if (WineInfo.isMainWineVersion(wineVersion)) {
            boolean result = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.getAssets(), "container_pattern_gamenative.tzst", containerDir, onExtractFileListener);

            if (result) {
                try {
                    JSONObject commonDlls = new JSONObject(FileUtils.readString(context, "common_dlls.json"));
                    extractCommonDlls("x86_64-windows", "system32", commonDlls, containerDir, onExtractFileListener);
                    extractCommonDlls("i386-windows", "syswow64", commonDlls, containerDir, onExtractFileListener);
                }
                catch (JSONException e) {
                    return false;
                }
            }

            return result;
        }
        else {
            File installedWineDir = ImageFs.find(context).getInstalledWineDir();
            WineInfo wineInfo = WineInfo.fromIdentifier(context, wineVersion);
            String suffix = wineInfo.fullVersion()+"-"+wineInfo.getArch();
            File file = new File(installedWineDir, "container-pattern-"+suffix+".tzst");
            return TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, file, containerDir, onExtractFileListener);
        }
    }
}
