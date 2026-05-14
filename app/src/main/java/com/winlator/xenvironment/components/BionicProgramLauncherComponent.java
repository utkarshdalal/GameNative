package com.winlator.xenvironment.components;

import android.app.Service;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.Image;
import android.net.ConnectivityManager;
import android.net.InetAddresses;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.ContactsContract;
import android.util.Log;

import androidx.annotation.NonNull;

import com.winlator.PrefManager;

import app.gamenative.utils.LsfgVkManager;
import com.winlator.box86_64.Box86_64Preset;
import com.winlator.box86_64.Box86_64PresetManager;
import com.winlator.container.Container;
import com.winlator.container.Shortcut;
import com.winlator.contents.ContentProfile;
import com.winlator.contents.ContentsManager;
import com.winlator.core.Callback;
import com.winlator.core.DefaultVersion;
import com.winlator.core.envvars.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GPUInformation;
import com.winlator.core.ProcessHelper;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.WineInfo;
import com.winlator.fexcore.FEXCorePreset;
import com.winlator.fexcore.FEXCorePresetManager;
import com.winlator.sysvshm.SysVSHMConnectionHandler;
import com.winlator.sysvshm.SysVSHMRequestHandler;
import com.winlator.sysvshm.SysVSharedMemory;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xenvironment.ImageFs;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import app.gamenative.PluviaApp;
import app.gamenative.events.AndroidEvent;
import app.gamenative.service.SteamService;

public class BionicProgramLauncherComponent extends GuestProgramLauncherComponent {
    private String guestExecutable;
    private static int pid = -1;
    private String[] bindingPaths;
    private EnvVars envVars;
    private WineInfo wineInfo;
    private String box64Version = DefaultVersion.BOX64;
    private String box64Preset = Box86_64Preset.COMPATIBILITY;
    private String fexcorePreset = FEXCorePreset.INTERMEDIATE;
    private Callback<Integer> terminationCallback;
    private static final Object lock = new Object();
    private boolean wow64Mode = true;
    private final ContentsManager contentsManager;
    private final ContentProfile wineProfile;
    private Container container;
    private File workingDir;

    public void setWineInfo(WineInfo wineInfo) {
        this.wineInfo = wineInfo;
    }
    public WineInfo getWineInfo() {
        return this.wineInfo;
    }

    public Container getContainer() { return this.container; }
    public void setContainer(Container container) { this.container = container; }

    /** Numeric Steam appid for the game in this container (e.g. "221380").
     *  Set from XServerScreen before start(); only consumed in real-Steam mode
     *  to publish SteamGameId / SteamAppId for the steam_helper handshake. */
    private String steamAppId;
    public void setSteamAppId(String steamAppId) { this.steamAppId = steamAppId; }

    public BionicProgramLauncherComponent(ContentsManager contentsManager, ContentProfile wineProfile) {
        this.contentsManager = contentsManager;
        this.wineProfile = wineProfile;
    }

    private Runnable preUnpack;
    public void setPreUnpack(Runnable r) { this.preUnpack = r; }
    @Override
    public void start() {
        synchronized (lock) {
            stop();
            if (wineInfo.isArm64EC())
                extractEmulatorsDlls();
            else
                extractBox64Files();
            if (preUnpack != null) preUnpack.run();
            pid = execGuestProgram();
            Log.d("BionicProgramLauncherComponent", "Process " + pid + " started");
            SteamService.setKeepAlive(true);
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (pid != -1) {
                Process.killProcess(pid);
                Log.d("BionicProgramLauncherComponent", "Stopped process " + pid);
                List<ProcessHelper.ProcessInfo> subProcesses = ProcessHelper.listSubProcesses();
                for (ProcessHelper.ProcessInfo subProcess : subProcesses) {
                    Process.killProcess(subProcess.pid);
                }
                SteamService.setKeepAlive(false);
            }
            execShellCommand("wineserver -k");
        }
    }

    public Callback<Integer> getTerminationCallback() {
        return terminationCallback;
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    public String getGuestExecutable() {
        return guestExecutable;
    }

    public void setGuestExecutable(String guestExecutable) {
        this.guestExecutable = guestExecutable;
    }

    public boolean isWoW64Mode() {
        return wow64Mode;
    }

    public void setWoW64Mode(boolean wow64Mode) {
        this.wow64Mode = wow64Mode;
    }

    public String[] getBindingPaths() {
        return bindingPaths;
    }

    public void setBindingPaths(String[] bindingPaths) {
        this.bindingPaths = bindingPaths;
    }

    public EnvVars getEnvVars() {
        return envVars;
    }

    public void setEnvVars(EnvVars envVars) {
        this.envVars = envVars;
    }

    public String getBox64Preset() {
        return box64Preset;
    }

    public void setBox64Preset(String box64Preset) {
        this.box64Preset = box64Preset;
    }

    public void setFEXCorePreset (String fexcorePreset) { this.fexcorePreset = fexcorePreset; }

    public File getWorkingDir() {
        return workingDir;
    }

    public void setWorkingDir(File workingDir) {
        this.workingDir = workingDir;
    }

    private int execGuestProgram() {

        final int MAX_PLAYERS = 1; // old static method

        // Get the number of enabled players directly from ControllerManager.
        final int enabledPlayerCount = MAX_PLAYERS;
        for (int i = 0; i < enabledPlayerCount; i++) {
            String memPath;
            if (i == 0) {
                // Player 1 uses the original, non-numbered path that is known to work.
                memPath = "/data/data/app.gamenative/files/imagefs/tmp/gamepad.mem";
            } else {
                // Players 2, 3, 4 use a 1-based index.
                memPath = "/data/data/app.gamenative/files/imagefs/tmp/gamepad" + i + ".mem";
            }

            File memFile = new File(memPath);
            memFile.getParentFile().mkdirs();
            try (RandomAccessFile raf = new RandomAccessFile(memFile, "rw")) {
                raf.setLength(64);
            } catch (IOException e) {
                Log.e("EVSHIM_HOST", "Failed to create mem file for player index "+i, e);
            }
        }
        Context context = environment.getContext();
        ImageFs imageFs = ImageFs.find(context);
        File rootDir = imageFs.getRootDir();

        PrefManager.init(context);
        boolean enableBox86_64Logs = PrefManager.getBoolean("enable_box86_64_logs", true);
        boolean shareAndroidClipboard = PrefManager.getBoolean("share_android_clipboard", false);
        boolean enablePebLogs = PrefManager.getBoolean("enable_peb_logs", false);

        // Always set this to defer handling to WineRequestComponent
        envVars.put("WINE_OPEN_WITH_ANDROID_BROwSER", "1"); // Pipetto wine has a typo, so we need 2 envvar for it to work
        envVars.put("WINE_OPEN_WITH_ANDROID_BROWSER", "1");

        if (shareAndroidClipboard) {
            envVars.put("WINE_FROM_ANDROID_CLIPBOARD", "1");
            envVars.put("WINE_TO_ANDROID_CLIPBOARD", "1");
        }
        if (enablePebLogs) {
            envVars.put("WINE_LOG_PEB_DATA", "1");
        }

        EnvVars envVars = new EnvVars();

        // Use the ControllerManager's dynamic count for the environment variable
        envVars.put("EVSHIM_MAX_PLAYERS", String.valueOf(enabledPlayerCount));
        if (true) {
            envVars.put("EVSHIM_SHM_ID", 1);
        }
        addBox64EnvVars(envVars, enableBox86_64Logs);
        envVars.putAll(FEXCorePresetManager.getEnvVars(context, fexcorePreset));

        String renderer = GPUInformation.getRenderer(context);

        if (renderer.contains("Mali"))
            envVars.put("BOX64_MMAP32", "0");

        if (envVars.get("BOX64_MMAP32").equals("1") && !wineInfo.isArm64EC())
            envVars.put("WRAPPER_DISABLE_PLACED", "1");

        // Setting up essential environment variables for Wine
        envVars.put("HOME", imageFs.home_path);
        envVars.put("USER", ImageFs.USER);
        envVars.put("TMPDIR", rootDir.getPath() + "/usr/tmp");
        envVars.put("DISPLAY", ":0");

        String winePath = imageFs.getWinePath() + "/bin";

        Log.d("BionicProgramLauncherComponent", "WinePath is " + winePath);

        envVars.put("PATH", winePath + ":" +
                rootDir.getPath() + "/usr/bin");

        envVars.put("LD_LIBRARY_PATH", rootDir.getPath() + "/usr/lib" + ":" + "/system/lib64");
        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir.getPath() + UnixSocketConfig.SYSVSHM_SERVER_PATH);
        envVars.put("FONTCONFIG_PATH", rootDir.getPath() + "/usr/etc/fonts");

        envVars.put("XDG_DATA_DIRS", rootDir.getPath() + "/usr/share");
        envVars.put("XDG_CONFIG_DIRS", rootDir.getPath() + "/usr/etc/xdg");
        envVars.put("GST_PLUGIN_PATH", rootDir.getPath() + "/usr/lib/gstreamer-1.0");
        envVars.put("VK_LAYER_PATH", rootDir.getPath() + "/usr/share/vulkan/implicit_layer.d" + ":" + rootDir.getPath() + "/usr/share/vulkan/explicit_layer.d");
        envVars.put("WINE_NO_DUPLICATE_EXPLORER", "1");
        envVars.put("PREFIX", rootDir.getPath() + "/usr");
        envVars.put("WINE_DISABLE_FULLSCREEN_HACK", "1");
        envVars.put("ENABLE_UTIL_LAYER", "1");
        envVars.put("GST_PLUGIN_FEATURE_RANK", "ximagesink:3000");
        envVars.put("ALSA_CONFIG_PATH", rootDir.getPath() + "/usr/share/alsa/alsa.conf" + ":" + rootDir.getPath() + "/usr/etc/alsa/conf.d/android_aserver.conf");
        envVars.put("ALSA_PLUGIN_DIR", rootDir.getPath() + "/usr/lib/alsa-lib");
        envVars.put("OPENSSL_CONF", rootDir.getPath() + "/usr/etc/tls/openssl.cnf");
        envVars.put("SSL_CERT_FILE", rootDir.getPath() + "/usr/etc/tls/cert.pem");
        envVars.put("SSL_CERT_DIR", rootDir.getPath() + "/usr/etc/tls/certs");
        envVars.put("WINE_X11FORCEGLX", "1");
        envVars.put("WINE_GST_NO_GL", "1");
        envVars.put("SteamGameId", "0");

        String primaryDNS = "8.8.4.4";
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Service.CONNECTIVITY_SERVICE);
        if (connectivityManager.getActiveNetwork() != null) {
            ArrayList<InetAddress> dnsServers = new ArrayList<>(connectivityManager.getLinkProperties(connectivityManager.getActiveNetwork()).getDnsServers());

            // Check if the dnsServers list is not empty before getting an item
            if (!dnsServers.isEmpty()) {
                primaryDNS = dnsServers.get(0).toString().substring(1);
            }
        }
        envVars.put("ANDROID_RESOLV_DNS", primaryDNS);
        envVars.put("WINE_NEW_NDIS", "1");

        String ld_preload = "";
        String sysvPath = imageFs.getLibDir() + "/libandroid-sysvshm.so";
        String evshimPath = imageFs.getLibDir() + "/libevshim.so";
        String replacePath = imageFs.getLibDir() + "/libredirect-bionic.so";

        if (new File(sysvPath).exists()) ld_preload += sysvPath;


        ld_preload += ":" + evshimPath;
        ld_preload += ":" + replacePath;

        envVars.put("LD_PRELOAD", ld_preload);

        envVars.put("EVSHIM_SHM_NAME", "controller-shm0");

        // Check for specific shared memory libraries
//        if ((new File(imageFs.getLibDir(), "libandroid-sysvshm.so")).exists()){
//            ld_preload = imageFs.getLibDir() + "/libandroid-sysvshm.so";
//        }

        //String nativeDir = context.getApplicationInfo().nativeLibraryDir; // e.g. /data/app/…/lib/arm64

        // Bionic-Steam mode: env vars required by Proton's lsteamclient.dll +
        // native libsteamclient.so bridge (loader path, IPC endpoint, VDF root).
        if (container != null && container.isLaunchBionicSteam()) {
            addRealSteamEnvVars(envVars, imageFs);
            // Boot the native libsteamclient.so inside *this* (Android) process
            // so Wine-side lsteamclient.dll has something to connect to.
            bootstrapNativeSteamClient(envVars, imageFs);
        }

        // Merge any additional environment variables from external sources
        if (this.envVars != null) {
            envVars.putAll(this.envVars);
        }

        if (LsfgVkManager.isSupported(container)) {
            LsfgVkManager.ensureRuntimeInstalled(environment.getContext(), container);
            LsfgVkManager.writeConfig(container);
            LsfgVkManager.applyLaunchEnv(container, envVars);
        }

        Log.d("BionicProgramLauncherComponent", "env vars are " + envVars.toString());

        String emulator = container.getEmulator();

        // Construct the command without Box64 to the Wine executable
        String command = "";
        String overriddenCommand = envVars.get("GUEST_PROGRAM_LAUNCHER_COMMAND");
        if (!overriddenCommand.isEmpty()) {
            String[] parts = overriddenCommand.split(";");
            for (String part : parts)
                command += part + " ";
            command = command.trim();
        }
        else {
            command = getFinalCommand(winePath, emulator, envVars, imageFs.getBinDir(), guestExecutable);
        }

        // **Maybe remove this: Set execute permissions for box64 if necessary (Glibc/Proot artifact)
        File box64File = new File(rootDir, "/usr/bin/box64");
        if (box64File.exists()) {
            FileUtils.chmod(box64File, 0755);
        }

        return ProcessHelper.exec(command, envVars.toStringArray(), workingDir != null ? workingDir : rootDir, (status) -> {
            synchronized (lock) {
                pid = -1;
            }
            if (!environment.isWinetricksRunning()) {
                SteamService.setKeepAlive(false);
                if (terminationCallback != null)
                    terminationCallback.call(status);
            }
        });
    }

    @NonNull
    private String getFinalCommand(String winePath, String emulator, EnvVars envVars, File binDir, String guestExecutable) {
        String command;
        if (wineInfo.isArm64EC()) {
            command = winePath + "/" + guestExecutable;
            if (emulator.toLowerCase().equals("fexcore"))
                envVars.put("HODLL", "libwow64fex.dll");
            else
                envVars.put("HODLL", "wowbox64.dll");
        }
        else
            command = binDir + "/box64 " + guestExecutable;
        return command;
    }

    private void extractBox64Files() {
        ImageFs imageFs = environment.getImageFs();
        Context context = environment.getContext();
        String box64Version = container.getBox64Version();

        Log.i("Extraction", "Extracting required box64 version: " + box64Version);
        File rootDir = imageFs.getRootDir();

        // No more version check, just extract directly.
        ContentProfile profile = contentsManager.getProfileByEntryName("box64-" + box64Version);
        if (profile != null) {
            contentsManager.applyContent(profile);
        } else {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.getAssets(), "box86_64/box64-" + box64Version + "-bionic.tzst", rootDir);
        }

        // Update the metadata so the container knows which version is installed.
        container.putExtra("box64Version", box64Version);
        container.saveData();

        // Set execute permissions.
        File box64File = new File(rootDir, "usr/bin/box64");
        if (box64File.exists()) {
            FileUtils.chmod(box64File, 0755);
        }
    }

    private void extractEmulatorsDlls() {
        Context context = environment.getContext();
        File rootDir = environment.getImageFs().getRootDir();
        File system32dir = new File(rootDir + "/home/xuser/.wine/drive_c/windows/system32");
        boolean containerDataChanged = false;

        ImageFs imageFs = ImageFs.find(context);

        String wowbox64Version = container.getBox64Version();
        String fexcoreVersion = container.getFEXCoreVersion();

        Log.d("Extraction", "box64Version in use: " + wowbox64Version);
        Log.d("Extraction", "fexcoreVersion in use: " + fexcoreVersion);

        ContentProfile wowboxprofile = contentsManager.getProfileByEntryName("wowbox64-" + wowbox64Version);
        if (wowboxprofile != null) {
            contentsManager.applyContent(wowboxprofile);
        } else {
            Log.d("Extraction", "Extracting box64Version: " + wowbox64Version);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, environment.getContext(), "wowbox64/wowbox64-" + wowbox64Version + ".tzst", system32dir);
        }
        container.putExtra("box64Version", wowbox64Version);
        containerDataChanged = true;

        ContentProfile fexprofile = contentsManager.getProfileByEntryName("fexcore-" + fexcoreVersion);
        if (fexprofile != null) {
            contentsManager.applyContent(fexprofile);
        } else {
            Log.d("Extraction", "Extracting fexcoreVersion: " + fexcoreVersion);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, environment.getContext(), "fexcore/fexcore-" + fexcoreVersion + ".tzst", system32dir);
        }
        container.putExtra("fexcoreVersion", fexcoreVersion);

        containerDataChanged = true;
        if (containerDataChanged) container.saveData();
    }

    private void addBox64EnvVars(EnvVars envVars, boolean enableLogs) {
        Context context = environment.getContext();
        ImageFs imageFs = ImageFs.find(context);
        envVars.put("BOX64_NOBANNER", ProcessHelper.PRINT_DEBUG && enableLogs ? "0" : "1");
        envVars.put("BOX64_DYNAREC", "1");

        if (enableLogs) {
            envVars.put("BOX64_LOG", "1");
            envVars.put("BOX64_DYNAREC_MISSING", "1");
        }

        envVars.putAll(Box86_64PresetManager.getEnvVars("box64", environment.getContext(), box64Preset));
        envVars.put("BOX64_X11GLX", "1");
        File box64RCFile = new File(imageFs.getRootDir(), "/etc/config.box64rc");
        envVars.put("BOX64_RCFILE", box64RCFile.getPath());
    }

    /**
     * Sets env vars required when launching with the real Steam client
     * (Proton's steam.exe + lsteamclient.dll talking to the native libsteamclient.so).
     *
     * Three groups:
     *   A. WINESTEAMCLIENTPATH{,64} — where lsteamclient.dll dlopens the native bridge.
     *   B. _STEAM_SETENV_MANAGER + friends — bootstrap-gate handshake checked by
     *      libsteamclient.so's init_process_env_manager_from_dll, plus the IPC endpoint
     *      overrides resolved in its endpoint string parser.
     *   C. SteamPath / SteamUser / SteamClientLaunch — Wine-side identity expected by
     *      steam_helper (steam.exe) and Steamworks games inside the prefix.
     *
     * STEAM_BASE_FOLDER points the native .so at the Linux-side directory that mirrors
     * the Windows `C:\Program Files (x86)\Steam` install, so its `<base>/config/config.vdf`
     * read lands on the file SteamTokenLogin.phase1SteamConfig() writes.
     */
    private void addRealSteamEnvVars(EnvVars envVars, ImageFs imageFs) {
        String steamRootLinux = imageFs.wineprefix + "/drive_c/Program Files (x86)/Steam";
        String breakpadDir = imageFs.getRootDir().getPath() + "/usr/tmp/breakpad";
        new File(breakpadDir).mkdirs();

        // A. lsteamclient.dll loader paths -> native Linux client/bridge .so
        envVars.put("WINESTEAMCLIENTPATH64", steamRootLinux + "/linux64/steamclient.so");
        envVars.put("WINESTEAMCLIENTPATH",   steamRootLinux + "/linux32/steamclient.so");

        // B. libsteamclient.so bootstrap-gate handshake (all required together)
        envVars.put("_STEAM_SETENV_MANAGER", "1");
        envVars.put("BREAKPAD_DUMP_LOCATION", breakpadDir);
        envVars.put("STEAM_BASE_FOLDER", steamRootLinux);
        envVars.put("ENABLE_VK_LAYER_VALVE_steam_overlay_1", "0");
        envVars.put("STEAMVIDEOTOKEN", "1");

        // IPC endpoints; override defaults if the MCP-hosted .so listens elsewhere
        envVars.put("Steam3Master",       "127.0.0.1:57343");
        envVars.put("SteamClientService", "127.0.0.1:57344");

        // C. Wine-side Steam identity for steam_helper / games
        String username = app.gamenative.PrefManager.INSTANCE.getUsername();
        if (username != null && !username.isEmpty()) {
            envVars.put("SteamUser", username);
            // Mirrors what the real Steam client publishes; some Steamworks
            // games (and steam_helper itself) read SteamAppUser as a fallback.
            envVars.put("SteamAppUser", username);
        }
        envVars.put("SteamClientLaunch", "1");
        envVars.put("SteamEnv", "1");
        envVars.put("SteamPath", "C:\\Program Files (x86)\\Steam");
        envVars.put("ValvePlatformMutex", "c:\\Program Files (x86)\\Steam/");

        long steamId64 = app.gamenative.PrefManager.INSTANCE.getSteamUserSteamId64();
        if (steamId64 != 0L) {
            envVars.put("STEAMID", Long.toString(steamId64));
        }

        // Override the SteamGameId=0 set above with the actual Steam appid for
        // this container, and publish the matching SteamAppId. Steamworks games
        // (and the steam.exe / steam_helper handshake) require both to be set
        // to the running game's appid for the IPC bridge to attach correctly.
        if (steamAppId != null && !steamAppId.isEmpty()) {
            envVars.put("SteamGameId", steamAppId);
            envVars.put("SteamAppId", steamAppId);

            try {
                int appIdInt = Integer.parseInt(steamAppId);
                int[] dlcs = app.gamenative.service.SteamService.getOwnedDlcAppIdsOf(appIdInt);
                if (dlcs != null && dlcs.length > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < dlcs.length; i++) {
                        if (i > 0) sb.append(',');
                        sb.append(dlcs[i]);
                    }
                    envVars.put("OWNED_DLCS", sb.toString());
                    Log.i("BionicProgramLauncherComponent",
                          "OWNED_DLCS=" + sb + " (count=" + dlcs.length + ")");
                }
            } catch (NumberFormatException nfe) {
                // steamAppId not numeric; the SteamBootstrap.prepareApp block
                // below will log the same condition and skip its own work.
            } catch (Throwable t) {
                Log.w("BionicProgramLauncherComponent",
                      "Failed to resolve owned DLCs for OWNED_DLCS env var", t);
            }
        }
        envVars.put("STEAM_LOG_LEVEL", "10");
        envVars.put("STEAM_DEBUG", "1");
        envVars.put("IPCLOGGING", "1");
        envVars.put("STEAMNETWORKINGSOCKETS_LOG_LEVEL", "verbose");
        envVars.put("NetworkVerbose", "1");
        envVars.put("SteamNetworkingSockets_Verbose", "4");
        envVars.put("SteamNetworkingSocketsLib_Verbose", "4");
        envVars.put("DebugNetworkConnections", "1");
    }

    /**
     * Loads libsteamclient.so into the Android process (via JNI -> dlopen) and
     * brings it up to a connected SteamClient + pipe + global-user state, so
     * Proton's lsteamclient.dll inside the Wine subprocess we launch next has
     * a live IPC peer.
     *
     * HOME handling: the native init calls setenv("HOME", ...) on the Android
     * process. The Wine subprocess we spawn afterwards is invoked with an
     * explicit envp via ProcessHelper.exec(...), which already contains the
     * project's own HOME (imageFs.home_path) — so the value we set here does
     * NOT leak into Wine.
     */
    private void bootstrapNativeSteamClient(EnvVars envVars, ImageFs imageFs) {
        // HOME for libsteamclient.so points at the parent of `Steam/`; the .so
        // resolves <HOME>/Steam/config/config.vdf etc. relative to it.
        String nativeHome = imageFs.wineprefix + "/drive_c/Program Files (x86)";
        // The Android-Steam build of libsteamclient.so ships inside our imagefs
        // (e.g. /data/data/app.gamenative/files/imagefs/usr/lib/libsteamclient.so).
        String libPath = new File(imageFs.getLibDir(), "libsteamclient.so").getAbsolutePath();

        File libFile = new File(libPath);
        if (!libFile.exists()) {
            Log.w("BionicProgramLauncherComponent",
                  "libsteamclient.so not found at " + libPath + "; skipping native bootstrap");
            return;
        }

        java.util.HashMap<String, String> extra = new java.util.HashMap<>();
        // bootstrap-gate handshake vars libsteamclient.so checks during init
        String[] passthrough = new String[] {
                "_STEAM_SETENV_MANAGER",
                "BREAKPAD_DUMP_LOCATION",
                "STEAM_BASE_FOLDER",
                "ENABLE_VK_LAYER_VALVE_steam_overlay_1",
                "STEAMVIDEOTOKEN",
                "SteamUser",
        };
        for (String key : passthrough) {
            String val = envVars.get(key);
            if (val != null && !val.isEmpty()) {
                extra.put(key, val);
            }
        }

        // Credentials for the explicit refresh-token logon path inside the .so.
        // PrefManager.username is the Steam account login name; refreshToken is
        // the JWT-style token Steam issued during our app login; steamUserSteamId64
        // is the 64-bit SteamID. If any are missing we fall back to whatever
        // cached auto-logon libsteamclient.so can do on its own.
        String accountName  = app.gamenative.PrefManager.INSTANCE.getUsername();
        String refreshToken = app.gamenative.PrefManager.INSTANCE.getRefreshToken();
        long   steamId64    = app.gamenative.PrefManager.INSTANCE.getSteamUserSteamId64();

        try {
            int rc = app.gamenative.SteamBootstrap.INSTANCE.start(
                    environment.getContext(),
                    libPath,
                    nativeHome,
                    envVars.get("Steam3Master"),
                    envVars.get("SteamClientService"),
                    extra,
                    accountName,
                    refreshToken,
                    steamId64);
            Log.i("BionicProgramLauncherComponent", "SteamBootstrap.start rc=" + rc);

            // Once the engine is logged on, kick PICS + encrypted-ticket
            // pre-warm for the app we're about to launch. The Wine-side
            // lsteamclient.dll has no path to drive these itself before the
            // game asks for an ownership ticket, so without this the launch
            // typically stalls at "Validating Subscriptions". Best-effort:
            // skipped silently if rc != 0 or steamAppId isn't a valid AppID.
            if (rc == 0 && steamAppId != null && !steamAppId.isEmpty()) {
                try {
                    int appIdInt = Integer.parseInt(steamAppId);
                    int[] dlcAppIds;
                    try {
                        dlcAppIds = app.gamenative.service.SteamService.getOwnedDlcAppIdsOf(appIdInt);
                    } catch (Throwable t) {
                        Log.w("BionicProgramLauncherComponent",
                              "getOwnedDlcAppIdsOf threw for appId=" + appIdInt
                              + "; proceeding with no DLCs", t);
                        dlcAppIds = new int[0];
                    }
                    Log.i("BionicProgramLauncherComponent",
                          "SteamBootstrap.prepareApp(" + appIdInt + ") with "
                          + dlcAppIds.length + " owned DLC(s)");
                    app.gamenative.SteamBootstrap.INSTANCE.prepareApp(appIdInt, dlcAppIds);
                } catch (NumberFormatException nfe) {
                    Log.w("BionicProgramLauncherComponent",
                          "steamAppId=" + steamAppId + " is not numeric; "
                          + "skipping SteamBootstrap.prepareApp");
                }
            }
        } catch (Throwable t) {
            Log.e("BionicProgramLauncherComponent", "SteamBootstrap.start threw", t);
        }
    }

    public String execShellCommand(String command) {
        return execShellCommand(command, true);
    }

    public String execShellCommand(String command, boolean includeStderr) {
        Context context = environment.getContext();
        ImageFs imageFs = ImageFs.find(context);
        File rootDir = imageFs.getRootDir();
        StringBuilder output = new StringBuilder();
        EnvVars envVars = new EnvVars();
        addBox64EnvVars(envVars, false);

        envVars.put("HOME", imageFs.home_path);
        envVars.put("USER", ImageFs.USER);
        envVars.put("TMPDIR", imageFs.getRootDir().getPath() + "/tmp");
        envVars.put("DISPLAY", ":0");

        String winePath = imageFs.getWinePath() + "/bin";

        Log.d("BionicProgramLauncherComponent", "WinePath is " + winePath);

        envVars.put("PATH", winePath + ":" + rootDir.getPath() + "/usr/bin");

        envVars.put("LD_LIBRARY_PATH", rootDir.getPath() + "/usr/lib" + ":" + "/system/lib64");
        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir.getPath() + UnixSocketConfig.SYSVSHM_SERVER_PATH);
        envVars.put("WINE_NO_DUPLICATE_EXPLORER", "1");
        envVars.put("PREFIX", rootDir.getPath() + "/usr");
        envVars.put("WINE_DISABLE_FULLSCREEN_HACK", "1");
        envVars.put("SteamGameId", "0");

        String ld_preload = "";
        String sysvPath = imageFs.getLibDir() + "/libandroid-sysvshm.so";
        String replacePath = imageFs.getLibDir() + "/libredirect-bionic.so";

        if (new File(sysvPath).exists()) ld_preload += sysvPath;

        ld_preload += ":" + replacePath;

        envVars.put("LD_PRELOAD", ld_preload);

        String emulator = container.getEmulator();
        if (this.envVars != null) envVars.putAll(this.envVars);

        String finalCommand = getFinalCommand(winePath, emulator, envVars, imageFs.getBinDir(), command);

        File box64File = new File(rootDir, "/usr/bin/box64");
        if (box64File.exists()) {
            FileUtils.chmod(box64File, 0755);
        }

        // Execute the command and capture its output.
        //
        // IMPORTANT: stderr MUST be drained concurrently with stdout, even when
        // includeStderr=false. Wine spits out a flood of fixme:/err: lines on
        // stderr; if we don't read it, the kernel's pipe buffer (~64 KB) fills,
        // wine's next write(stderr,...) blocks, and the whole subprocess hangs
        // forever -- which then deadlocks our stdout read too. SteamTokenLogin
        // calls this with includeStderr=false, so this used to hang on boot.
        try {
            Log.d("BionicProgramLauncherComponent", "Shell command is " + finalCommand);
            java.lang.Process process = Runtime.getRuntime().exec(finalCommand, envVars.toStringArray(), workingDir != null ? workingDir : imageFs.getRootDir());
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            final StringBuilder stderrBuf = new StringBuilder();
            Thread stderrPump = new Thread(() -> {
                try {
                    String l;
                    while ((l = errorReader.readLine()) != null) {
                        if (includeStderr) stderrBuf.append(l).append('\n');
                        // else: discard, but we MUST still consume the stream
                    }
                } catch (IOException ignored) {
                    // Subprocess closed stderr; fine.
                }
            }, "execShellCommand-stderr-pump");
            stderrPump.setDaemon(true);
            stderrPump.start();

            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            process.waitFor();
            stderrPump.join();
            if (includeStderr) output.append(stderrBuf);
        } catch (Exception e) {
            output.append("Error: ").append(e.getMessage());
        }

        // Format output: trim trailing whitespace/newlines
        return output.toString().trim();
    }

    public void restartWineServer() {
        ProcessHelper.terminateAllWineProcesses();
        pid = execGuestProgram();
        Log.d("BionicProgramLauncherComponent", "Wine restarted successfully");

    }
}
