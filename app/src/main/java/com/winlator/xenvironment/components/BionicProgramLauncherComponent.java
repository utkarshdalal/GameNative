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
import android.system.Os;
import android.provider.ContactsContract;
import android.util.Log;
import android.view.Display;

import androidx.annotation.NonNull;

import com.winlator.PrefManager;

import app.gamenative.PluviaApp;
import app.gamenative.display.GamescopeSettings;
import app.gamenative.utils.LsfgVkManager;
import com.winlator.box86_64.Box86_64Preset;
import com.winlator.box86_64.Box86_64PresetManager;
import com.winlator.container.Container;
import com.winlator.container.ContainerOverlay;
import com.winlator.container.Shortcut;
import com.winlator.contents.ContentProfile;
import com.winlator.contents.ContentsManager;
import com.winlator.core.Callback;
import com.winlator.core.DefaultVersion;
import com.winlator.core.envvars.EnvVarRedaction;
import com.winlator.core.envvars.EnvVars;
import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.core.GPUInformation;
import com.winlator.core.ProcessHelper;
import com.winlator.core.SharedComponents;
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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import app.gamenative.BuildConfig;
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

    // Resolve which libredirect shim to preload. Normally the flavor default
    // (PRELOAD_BIONIC_SO). When the container disables libredirect, modern falls
    // back to the W^X-only minimal shim (still required to run Wine on a strict
    // W^X kernel) and legacy preloads nothing. Returns null to preload nothing.
    private String resolveLibredirectPreload(ImageFs imageFs) {
        if (container != null && container.isDisableLibredirect()) {
            if (BuildConfig.MODERN_ANDROID) {
                return imageFs.getLibDir() + "/libredirect-bionic-wx-minimal.so";
            }
            return null;
        }
        return imageFs.getLibDir() + "/" + BuildConfig.PRELOAD_BIONIC_SO;
    }

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

        final int MAX_PLAYERS = 4;

        // Get the number of enabled players directly from ControllerManager.
        for (int i = 0; i < MAX_PLAYERS; i++) {
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
        envVars.put("EVSHIM_MAX_PLAYERS", String.valueOf(MAX_PLAYERS));
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
        new File(imageFs.home_path + "/.wine/drive_c" + rootDir.getPath() + "/usr/tmp").mkdirs();

        // Gamescope owns DISPLAY for its children (its Xwayland); only X11 mode claims :0 here.
        final boolean gamescopeMode = isGamescopeMode();
        if (!gamescopeMode) {
            envVars.put("DISPLAY", ":0");
        }
        else {
            // Both are read on the GUEST side and must be in this envp: GNDRM_HDR is what makes KMS
            // serve the 10-bit PQ surface formats a title needs to see HDR at all, and DXVK_HDR is what makes
            // DXVK report an HDR10 output to the game.
            final boolean hdr = isHdrSession();
            envVars.put("GNDRM_HDR", hdr ? "1" : "0");
            if (hdr) envVars.put("DXVK_HDR", "1");
        }

        String winePath = imageFs.getWinePath() + "/bin";

        Log.d("BionicProgramLauncherComponent", "WinePath is " + winePath);

        envVars.put("PATH", winePath + ":" +
                rootDir.getPath() + "/usr/bin");
        if (BuildConfig.MODERN_ANDROID) envVars.put("REDIRECT_EXEC__PROC_SELF_EXE", winePath + "/wine");

        String ldLibraryPath = rootDir.getPath() + "/usr/lib" + ":" + "/system/lib64";
        if (BuildConfig.MODERN_ANDROID) ldLibraryPath += ":" + imageFs.getWinePath() + "/lib";
        envVars.put("LD_LIBRARY_PATH", ldLibraryPath);
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
        // Wine's crypt32 imports this bundle into the Windows ROOT certificate store. Without it
        // the store only holds Wine's built-in Microsoft roots: Android 14+ moved the system CAs
        // to an APEX path Wine does not scan, so TLS verification fails for anything that trusts
        // via the Windows store (e.g. the EOS SDK's websockets, winhttp/wininet callers).
        envVars.put("WINE_ADDITIONAL_CERTS_DIR", rootDir.getPath() + "/usr/etc/tls/cert.pem");
        envVars.put("WINE_X11FORCEGLX", "1");
        envVars.put("WINE_GST_NO_GL", "1");
        envVars.put("SteamGameId", "0");

        String primaryDNS = "8.8.4.4";
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Service.CONNECTIVITY_SERVICE);
        if (connectivityManager.getActiveNetwork() != null) {
            ArrayList<InetAddress> dnsServers = new ArrayList<>(connectivityManager.getLinkProperties(connectivityManager.getActiveNetwork()).getDnsServers());

            // Check if the dnsServers list is not empty before getting an item
            if (!dnsServers.isEmpty()) {
                InetAddress selectedDNS = dnsServers.get(0);
                for (InetAddress dnsServer : dnsServers) {
                    if (dnsServer instanceof Inet4Address) {
                        selectedDNS = dnsServer;
                        break;
                    }
                }
                primaryDNS = selectedDNS.getHostAddress();
            }
        }
        envVars.put("ANDROID_RESOLV_DNS", primaryDNS);
        envVars.put("WINE_NEW_NDIS", "1");

        String ld_preload = "";
        String sysvPath = imageFs.getLibDir() + "/libandroid-sysvshm.so";
        String evshimPath = context.getApplicationInfo().nativeLibraryDir + "/libevshim.so";
        String replacePath = resolveLibredirectPreload(imageFs);

        if (new File(sysvPath).exists()) ld_preload += sysvPath;


        ld_preload += ":" + evshimPath;
        String dnsV4MappedPath = context.getApplicationInfo().nativeLibraryDir + "/libgamenative_dns_v4mapped.so";
        if (new File(dnsV4MappedPath).exists()) {
            ld_preload += ":" + dnsV4MappedPath;
        }
        if (replacePath != null) ld_preload += ":" + replacePath;

        envVars.put("LD_PRELOAD", ld_preload);
        envVars.put("EVSHIM_WINE", 1);
        envVars.put("EVSHIM_SHM_NAME", "controller-shm0");

        if (container != null && container.isFasterExternalLoading()) {
            String ffpGameDir = null;
            for (String[] drive : Container.drivesIterator(container.getDrives())) {
                if (drive[0].equals("A")) {
                    try {
                        ffpGameDir = new File(drive[1]).getCanonicalPath();
                    } catch (IOException e) {
                        ffpGameDir = drive[1];
                    }
                    break;
                }
            }
            if (ffpGameDir != null && ffpGameDir.startsWith("/storage/")
                    && !ffpGameDir.startsWith("/storage/emulated/")) {
                envVars.put("FFP_ENABLE", "1");
                envVars.put("FFP_MARKERS", "/steamapps/common/;/dosdevices/a:");
            }
        }

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

        if (BuildConfig.XR_BUILD) {
            String shimPath = context.getApplicationInfo().nativeLibraryDir + "/libkgslshim.so";
            if (new File(shimPath).exists()) {
                String cur = envVars.get("LD_PRELOAD");
                envVars.put("LD_PRELOAD", cur.isEmpty() ? shimPath : shimPath + ":" + cur);
            }
        }

        if (LsfgVkManager.isSupported(container)) {
            LsfgVkManager.ensureRuntimeInstalled(environment.getContext(), container);
            LsfgVkManager.writeConfig(container);
            // LSFG is an X11-arm feature. Under gamescope the compositor owns presentation and pacing, and the
            // lsfg-vk layer's failed device init on this driver's Turnip takes gamescope's own vkCreateDevice
            // down with it — so a gamescope session must not load the layer at all.
            if (gamescopeMode) {
                LsfgVkManager.disableLayer(container);
            } else {
                LsfgVkManager.applyLaunchEnv(container, envVars);
                if (LsfgVkManager.isArmed(container)) {
                    final android.content.Context lsfgContext = environment.getContext();
                    new Thread(() -> LsfgVkManager.prepareNativeCache(lsfgContext, container),
                        "lsfg-native-cache").start();
                }
            }
        }

        try {
            ContainerOverlay.applyBionicLaunchEnv(context, container, envVars);
        }
        catch (IllegalStateException e) {
            Log.e("BionicProgramLauncherComponent", e.getMessage());
            return -1;
        }

        if (gamescopeMode) {
            applyGamescopeEnv(context, envVars, rootDir);
        }

        Log.d("BionicProgramLauncherComponent", "env vars are " + EnvVarRedaction.redact(envVars));

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
            command = getFinalCommand(winePath, emulator, envVars, imageFs.getBinDir(), guestExecutable, gamescopeMode);
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

    // Gamescope payload paths: everything is payload-relative (build-host prefixes don't exist on device).
    private void applyGamescopeEnv(Context context, EnvVars envVars, File rootDir) {
        String gsDir = context.getFilesDir().getPath() + "/gamescope";

        // Append the shim, never prepend: its opens pass straight through to syscalls, so going first would hide the base prefix's DLLs from libgnoverlay/wine.
        String preload = envVars.get("LD_PRELOAD");
        envVars.put("LD_PRELOAD", preload.isEmpty()
                ? gsDir + "/lib/libgndrm_shim.so"
                : preload + ":" + gsDir + "/lib/libgndrm_shim.so");
        String libPath = envVars.get("LD_LIBRARY_PATH");
        envVars.put("LD_LIBRARY_PATH", libPath.isEmpty() ? gsDir + "/lib" : gsDir + "/lib:" + libPath);
        // gamescope execvp's gamescopereaper and wlroots spawns xkbcomp.
        String path = envVars.get("PATH");
        envVars.put("PATH", path.isEmpty() ? gsDir + "/bin" : gsDir + "/bin:" + path);

        File xdgRunDir = new File(rootDir, "tmp/xdg-run");
        xdgRunDir.mkdirs();
        FileUtils.chmod(xdgRunDir, 0700);
        envVars.put("XDG_RUNTIME_DIR", xdgRunDir.getPath());
        envVars.put("GAMENATIVE_DRM_SOCKET", "gndrm");

        envVars.put("XKB_CONFIG_ROOT", gsDir + "/share/xkeyboard-config-2");
        envVars.put("GAMESCOPE_PNP_IDS_PATH", gsDir + "/share/hwdata/pnp.ids");
        envVars.put("__EGL_VENDOR_LIBRARY_DIRS", gsDir + "/share/glvnd/egl_vendor.d");
        envVars.put("LIBGL_DRIVERS_PATH", gsDir + "/lib/dri");
        envVars.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
        envVars.put("WLR_XWAYLAND", gsDir + "/bin/Xwayland");

        // Non-Adreno (system Vulkan driver): glamor over zink stalls Xwayland, and Vulkan guests present through the shim.
        if (!new File("/dev/kgsl-3d0").exists())
            envVars.put("XWAYLAND_NO_GLAMOR", "1");

        // wlroots binds the X11 sockets here; gamescope's libX11 would resolve ":0" to a build-host dir, so it gets the socket path.
        String x11SocketDir = rootDir.getPath() + "/usr/tmp";
        new File(x11SocketDir).mkdirs();
        envVars.put("WLR_X11_SOCKET_DIR", x11SocketDir);
        envVars.put("GAMESCOPE_X11_DISPLAY", x11SocketDir + "/.X11-unix/X0");

        File sessionIcd = new File(context.getFilesDir(), "gamescope/share/vulkan/icd.d/adrenotools_gamescope.aarch64.json");
        envVars.put("VK_ICD_FILENAMES", sessionIcd.getAbsolutePath());

        if (container != null && container.getGraphicsDriver() != null)
            envVars.put("GNDRM_DRIVER_TYPE", container.getGraphicsDriver());

        // The guest image name for the session reaper's game-exit watch.
        String gameExecutable = container != null ? container.getExecutablePath() : "";
        if (gameExecutable != null && !gameExecutable.isEmpty()) {
            int separator = Math.max(gameExecutable.lastIndexOf('/'), gameExecutable.lastIndexOf('\\'));
            String gameImage = separator >= 0 ? gameExecutable.substring(separator + 1) : gameExecutable;
            if (!gameImage.isEmpty())
                envVars.put("GAMENATIVE_GAME_PROCESS", gameImage);
        }

        // Same source as gamescope's -W/-H, so the two cannot drift.
        int[] scanoutOutput = displaySize();
        if (scanoutOutput == null)
            scanoutOutput = AppUtils.parseScreenSize(container != null ? container.getScreenSize() : null);
        if (scanoutOutput != null)
            envVars.put("GNDRM_SCANOUT", scanoutOutput[0] + "x" + scanoutOutput[1]);

        Log.i("BionicProgramLauncherComponent", "Gamescope display server active; bin="
                + new File(gsDir, "bin/gamescope").exists() + " shim="
                + new File(gsDir, "lib/libgndrm_shim.so").exists() + " xwayland="
                + new File(gsDir, "bin/Xwayland").exists() + " xdg=" + xdgRunDir.getPath());
    }

    /** Mirrors XServerScreen.resolveGamescopeMode; a session override (setGamescopeModeOverride) wins to force X11 for the open-container session (the setup/pre-install session has no separate arm and follows the game arm). */
    private boolean isGamescopeMode() {
        if (gamescopeModeOverride != null) return gamescopeModeOverride;
        if (container == null) return false;
        return Container.DISPLAY_SERVER_GAMESCOPE.equalsIgnoreCase(container.getDisplayServer());
    }

    /** null = decide from the container/profile (see isGamescopeMode). */
    private Boolean gamescopeModeOverride = null;

    public void setGamescopeModeOverride(Boolean override) {
        gamescopeModeOverride = override;
    }

    /** @param isGamescope true only for the guest launch; shell commands (e.g. wineserver -k) run with a different env and would fail to load the gamescope binary. */
    @NonNull
    private String getFinalCommand(String winePath, String emulator, EnvVars envVars, File binDir, String guestExecutable, boolean isGamescope) {
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

        if (isGamescope) {
            // -W/-H = output (Surface/mode) size, not the game size; --expose-wayland keeps gamescope's socket for KMS; XWayland (count 1) serves wine's X11.
            command = wrapWithGamescope(command);
        }

        return command;
    }

    /** Prefix {@code command} with the gamescope wrapper used for guest launches. */
    private String wrapWithGamescope(String command) {
        String gamescope = environment.getContext().getFilesDir().getPath() + "/gamescope/bin/gamescope";
        linkReshadeShaders();
        // HDR output is asked for only when the container asks AND the panel reports HDR types.
        Display.HdrCapabilities hdrCaps = PluviaApp.getDisplayHdrCapabilities();
        final boolean hdr = isHdrSession(hdrCaps);
        setGndrmHdrEnv(hdr, hdrCaps);
        // No --hdr-itm-enabled: inverse tone mapping is for SDR input and forces full composition on every
        // frame.
        // The effect the QuickMenu last chose is applied here too, so a relaunch keeps it. A path the
        // manifest no longer lists is dropped rather than handed to gamescope as a missing file.
        String reshade = container != null ? container.getExtra(GamescopeSettings.EXTRA_RESHADE, "") : "";
        if (!reshade.isEmpty() && !manifestShaders(environment.getContext()).contains(reshade)) reshade = "";
        return gamescope + " --backend drm --expose-wayland"
                + (hdr ? " --hdr-enabled" : "")
                + (reshade.isEmpty() ? "" : " --reshade-effect " + reshade)
                + GamescopeSettings.scalerArg(container)
                + GamescopeSettings.gridArg()
                + gamescopeModeGeometry() + " -- " + command;
    }

    /** The effect paths `all_shaders.json` lists, as staged in `<files>/reshade`. */
    private static java.util.Set<String> manifestShaders(Context context) {
        java.util.Set<String> paths = new java.util.HashSet<>();
        try {
            java.io.File manifest = new java.io.File(context.getFilesDir(), "reshade/all_shaders.json");
            org.json.JSONArray entries = new org.json.JSONArray(FileUtils.readString(manifest));
            for (int i = 0; i < entries.length(); i++) {
                org.json.JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                String path = entry.optString("path");
                if (!path.isEmpty()) paths.add(path);
            }
        }
        catch (Exception e) {
            Log.w("BionicProgramLauncher", "ReShade manifest unreadable: " + e.getMessage());
        }
        return paths;
    }

    /**
     * Copy each pack under `assets/reshade` to `<files>/reshade/Shaders/<pack>` and the manifest to
     * the root, the layout gamescope resolves an effect path against. The staged copy is rebuilt every
     * launch so it cannot drift from the APK.
     */
    private static void stageReshadeShaders(Context context, File root) {
        // The APK is the source of truth: a shader removed from it must disappear on device too.
        if (root.exists()) FileUtils.delete(root);
        root.mkdirs();
        try {
            String[] entries = context.getAssets().list("reshade");
            if (entries == null) return;
            for (String entry : entries) {
                // copy() appends the asset's own name only when the destination is a directory
                // already, so each destination is built rather than left to it.
                if (FileUtils.isDirectory(context, "reshade/" + entry))
                    FileUtils.copy(context, "reshade/" + entry, new File(new File(root, "Shaders"), entry));
                else FileUtils.copy(context, "reshade/" + entry, new File(root, entry));
            }
        }
        catch (java.io.IOException e) {
            Log.w("BionicProgramLauncher", "ReShade staging failed: " + e.getMessage());
        }
    }

    /**
     * Stage the bundled ReShade shaders and point the container home at them. gamescope resolves an
     * effect name against `$HOME/.local/share/gamescope/reshade/Shaders`, and `$HOME` is the shared
     * `home/xuser` link, so the shaders are copied out of the APK once per launch and only the link
     * varies by container.
     */
    private void linkReshadeShaders() {
        Context context = environment.getContext();
        File shaders = new File(context.getFilesDir(), "reshade");
        stageReshadeShaders(context, shaders);
        File target = new File(new File(context.getFilesDir(), "imagefs/home/xuser"), ".local/share/gamescope/reshade");
        try {
            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            if (java.nio.file.Files.isSymbolicLink(target.toPath()) || target.isFile())
                target.delete();
            else if (target.isDirectory())
                com.winlator.core.FileUtils.delete(target);
            com.winlator.core.FileUtils.symlink(shaders.getAbsolutePath(), target.getPath());
            Log.i("BionicProgramLauncher", "ReShade shaders linked from " + shaders);
        }
        catch (Exception e) {
            Log.w("BionicProgramLauncher", "ReShade shader link failed: " + e.getMessage());
        }
    }

    /** HDR output is ours to ask for only when the container asks AND the panel reports HDR types. */
    private boolean isHdrSession() {
        return container != null && container.isHdrEnabled() && PluviaApp.getDisplayHdrCapabilities() != null;
    }

    /** Same test, against a capability read the caller already has. */
    private boolean isHdrSession(Display.HdrCapabilities hdrCaps) {
        return container != null && container.isHdrEnabled() && hdrCaps != null;
    }

    /** Set the daemon's HDR-output intent and the panel luminances in this process's environment. */
    private static void setGndrmHdrEnv(boolean hdr, Display.HdrCapabilities caps) {
        try {
            Os.setenv("GNDRM_HDR", hdr ? "1" : "0", true);
            if (hdr && caps != null) {
                Os.setenv("GNDRM_HDR_MAX_NITS", Float.toString(caps.getDesiredMaxLuminance()), true);
                Os.setenv("GNDRM_HDR_FALL_NITS", Float.toString(caps.getDesiredMaxAverageLuminance()), true);
                Os.setenv("GNDRM_HDR_MIN_NITS", Float.toString(caps.getDesiredMinLuminance()), true);
            }
        }
        catch (Exception ignored) {
            // The daemon reads the variable defensively; a failure leaves SDR output.
        }
    }

    /** "-W <w> -H <h> -w <w> -h <h>": -W/-H from the real display (matches the panel, no resample), -w/-h from the container screen size; either pair omitted if unknown. */
    private String gamescopeModeGeometry() {
        int[] containerSize = AppUtils.parseScreenSize(container != null ? container.getScreenSize() : null);
        int[] output = displaySize();
        if (output == null) output = containerSize;
        StringBuilder args = new StringBuilder();
        if (output != null) args.append(" -W ").append(output[0]).append(" -H ").append(output[1]);
        if (containerSize != null) args.append(" -w ").append(containerSize[0]).append(" -h ").append(containerSize[1]);
        return args.toString();
    }

    /**
     * The panel's real pixel size, or null if unreadable. This is what scanout needs: the
     * container's screen size is an internal desktop resolution, so using it here would make
     * gamescope present at 720p (or whatever preset) and resample onto the panel.
     */
    private static int[] displaySize() {
        int[] nativeSize = AppUtils.parseScreenSize(PluviaApp.getNativeDisplaySize());
        if (nativeSize != null) return nativeSize;
        return AppUtils.parseScreenSize(PluviaApp.getDefaultScreenSize());
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
            SharedComponents.extractAndLink(environment.getContext(), "wowbox64-" + wowbox64Version, TarCompressorUtils.Type.ZSTD, "wowbox64/wowbox64-" + wowbox64Version + ".tzst", system32dir, null);
        }
        container.putExtra("box64Version", wowbox64Version);
        containerDataChanged = true;

        ContentProfile fexprofile = contentsManager.getProfileByEntryName("fexcore-" + fexcoreVersion);
        if (fexprofile != null) {
            contentsManager.applyContent(fexprofile);
        } else {
            Log.d("Extraction", "Extracting fexcoreVersion: " + fexcoreVersion);
            SharedComponents.extractAndLink(environment.getContext(), "fexcore-" + fexcoreVersion, TarCompressorUtils.Type.ZSTD, "fexcore/fexcore-" + fexcoreVersion + ".tzst", system32dir, null);
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
        // ISteamUtils::IsOverlayEnabled() is not engine state -- it resolves in-process to
        // `getenv("SteamOS") ? true : dlsym(RTLD_DEFAULT, "IsOverlayEnabled")`. No overlay
        // module is loaded here (the bionic asset set ships none), so without this it returns
        // false and games that gate their invite/host UI on it refuse to open it.
        envVars.put("SteamOS", "1");
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
        }
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
                "SteamOS",
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
                    Log.i("BionicProgramLauncherComponent",
                          "SteamBootstrap.prepareApp(" + appIdInt + ")");
                    app.gamenative.SteamBootstrap.INSTANCE.prepareApp(appIdInt);
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
        return execShellCommand(command, includeStderr, null, null);
    }

    public String execShellCommand(String command, boolean includeStderr, EnvVars extraEnv, String[] unsetEnv) {
        Context context = environment.getContext();
        ImageFs imageFs = ImageFs.find(context);
        File rootDir = imageFs.getRootDir();
        EnvVars envVars = new EnvVars();
        addBox64EnvVars(envVars, false);

        envVars.put("HOME", imageFs.home_path);
        envVars.put("USER", ImageFs.USER);
        envVars.put("TMPDIR", imageFs.getRootDir().getPath() + "/tmp");
        envVars.put("DISPLAY", ":0");

        String winePath = imageFs.getWinePath() + "/bin";

        Log.d("BionicProgramLauncherComponent", "WinePath is " + winePath);

        envVars.put("PATH", winePath + ":" + rootDir.getPath() + "/usr/bin");
        if (BuildConfig.MODERN_ANDROID) envVars.put("REDIRECT_EXEC__PROC_SELF_EXE", winePath + "/wine");

        String ldLibraryPath = rootDir.getPath() + "/usr/lib" + ":" + "/system/lib64";
        if (BuildConfig.MODERN_ANDROID) ldLibraryPath += ":" + imageFs.getWinePath() + "/lib";
        envVars.put("LD_LIBRARY_PATH", ldLibraryPath);
        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir.getPath() + UnixSocketConfig.SYSVSHM_SERVER_PATH);
        envVars.put("WINE_NO_DUPLICATE_EXPLORER", "1");
        envVars.put("PREFIX", rootDir.getPath() + "/usr");
        envVars.put("WINE_DISABLE_FULLSCREEN_HACK", "1");
        envVars.put("SteamGameId", "0");

        String ld_preload = "";
        String sysvPath = imageFs.getLibDir() + "/libandroid-sysvshm.so";
        String replacePath = resolveLibredirectPreload(imageFs);

        if (new File(sysvPath).exists()) ld_preload += sysvPath;

        if (replacePath != null) ld_preload += ":" + replacePath;

        envVars.put("LD_PRELOAD", ld_preload);

        String emulator = container.getEmulator();
        if (this.envVars != null) envVars.putAll(this.envVars);
        try {
            ContainerOverlay.applyBionicLaunchEnv(context, container, envVars);
        }
        catch (IllegalStateException e) {
            Log.e("BionicProgramLauncherComponent", e.getMessage());
            return "";
        }
        if (extraEnv != null) envVars.putAll(extraEnv);
        if (unsetEnv != null) for (String name : unsetEnv) envVars.remove(name);

        // Never gamescope-wrap shell commands (wineserver -k, winetricks, ...): they run wine directly with this method's env.
        String finalCommand = getFinalCommand(winePath, emulator, envVars, imageFs.getBinDir(), command, false);

        File box64File = new File(rootDir, "/usr/bin/box64");
        if (box64File.exists()) {
            FileUtils.chmod(box64File, 0755);
        }

        Log.d("BionicProgramLauncherComponent", "Shell command is " + finalCommand);
        return ProcessHelper.execWithOutput(finalCommand, envVars.toStringArray(),
                workingDir != null ? workingDir : imageFs.getRootDir(), includeStderr);
    }

    public void restartWineServer() {
        ProcessHelper.terminateAllWineProcesses();
        pid = execGuestProgram();
        Log.d("BionicProgramLauncherComponent", "Wine restarted successfully");

    }
}
