package com.winlator.xenvironment.components;

import android.content.Context;
import android.icu.util.TimeZone;
import android.os.Process;
import android.util.Log;

import com.winlator.PrefManager;
import com.winlator.box86_64.Box86_64Preset;
import com.winlator.box86_64.Box86_64PresetManager;
import com.winlator.container.Container;
import com.winlator.core.Callback;
import com.winlator.core.DefaultVersion;
import com.winlator.core.FileUtils;
import com.winlator.core.WineInfo;
import com.winlator.core.envvars.EnvVars;
import com.winlator.core.ProcessHelper;
import com.winlator.core.TarCompressorUtils;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.ImageFs;
import com.winlator.xenvironment.XEnvironment;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class GuestProgramLauncherComponent extends EnvironmentComponent {
    private String guestExecutable;
    private static int pid = -1;
    private String[] bindingPaths;
    private EnvVars envVars;
    private String box86Version = DefaultVersion.BOX86;
    private String box64Version = DefaultVersion.BOX64;
    private String box86Preset = Box86_64Preset.COMPATIBILITY;
    private String box64Preset = Box86_64Preset.COMPATIBILITY;
    private Callback<Integer> terminationCallback;
    private static final Object lock = new Object();
    private boolean wow64Mode = true;
    private File workingDir;
    private WineInfo wineInfo;
    private Container container;

    private Runnable preUnpack;
    private String steamType;

    public void setWineInfo(WineInfo wineInfo) {
        this.wineInfo = wineInfo;
    }
    public WineInfo getWineInfo() {
        return this.wineInfo;
    }

    public Container getContainer() { return this.container; }
    public void setContainer(Container container) { this.container = container; }

    public void setPreUnpack(Runnable r) { this.preUnpack = r; }
    @Override
    public void start() {
        // Log.d("GuestProgramLauncherComponent", "Starting...");
        synchronized (lock) {
            stop();
            extractBox86_64Files();
            pid = execGuestProgram();
            Log.d("GuestProgramLauncherComponent", "Process " + pid + " started");
        }
    }

    @Override
    public void stop() {
        // Log.d("GuestProgramLauncherComponent", "Stopping...");
        synchronized (lock) {
            if (pid != -1) {
                Process.killProcess(pid);
                Log.d("GuestProgramLauncherComponent", "Stopped process " + pid);
                pid = -1;
                List<ProcessHelper.ProcessInfo> subProcesses = ProcessHelper.listSubProcesses();
                for (ProcessHelper.ProcessInfo subProcess : subProcesses) {
                    Log.d("GuestProgramLauncherComponent",
                            "Sub-process still running: "
                                    + subProcess.name + " | "
                                    + subProcess.pid + " | "
                                    + subProcess.ppid + ", stopping..."
                    );
                    Process.killProcess(subProcess.pid);
                }
            }
        }
    }

    public Callback<Integer> getTerminationCallback() {
        return terminationCallback;
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    public String getSteamType() { return steamType; }
    public void setSteamType(String steamType) {
        if (steamType == null) {
            this.steamType = Container.STEAM_TYPE_NORMAL;
            return;
        }
        String normalized = steamType.toLowerCase();
        switch (normalized) {
            case Container.STEAM_TYPE_LIGHT:
                this.steamType = Container.STEAM_TYPE_LIGHT;
                break;
            case Container.STEAM_TYPE_ULTRALIGHT:
                this.steamType = Container.STEAM_TYPE_ULTRALIGHT;
                break;
            default:
                this.steamType = Container.STEAM_TYPE_NORMAL;
        }
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

    public String getBox86Version() { return box86Version; }

    public void setBox86Version(String box86Version) { this.box86Version = box86Version; }

    public String getBox64Version() { return box64Version; }

    public void setBox64Version(String box64Version) { this.box64Version = box64Version; }

    public String getBox86Preset() {
        return box86Preset;
    }

    public void setBox86Preset(String box86Preset) {
        this.box86Preset = box86Preset;
    }

    public String getBox64Preset() {
        return box64Preset;
    }

    public void setBox64Preset(String box64Preset) {
        this.box64Preset = box64Preset;
    }

    public File getWorkingDir() {
        return workingDir;
    }

    public void setWorkingDir(File workingDir) {
        this.workingDir = workingDir;
    }

    private int execGuestProgram() {
        Context context = environment.getContext();

        PrefManager.init(context);
        boolean enableBox86_64Logs = PrefManager.getBoolean("enable_box86_64_logs", false);

        EnvVars envVars = new EnvVars();
        if (!wow64Mode) addBox86EnvVars(envVars, enableBox86_64Logs);
        addBox64EnvVars(envVars, enableBox86_64Logs);
        if (this.envVars != null) envVars.putAll(this.envVars);

        return exec(context, !wow64Mode, bindingPaths, envVars, terminationCallback, "box64 " + guestExecutable, workingDir);
    }
    public static int exec(Context context, String prootCmd) {
        return exec(context, false, new String[0], null, null, prootCmd, null);
    }
    public static int exec(Context context, boolean proot32, String[] bindingPaths, EnvVars extraVars, Callback<Integer> terminationCallback, String prootCmd, File workingDir) {
        Log.d("GuestProgramLauncherComponent", "Executing guest program");
        // Context context = environment.getContext();
        // ImageFs imageFs = environment.getImageFs();
        ImageFs imageFs = ImageFs.find(context);
        File rootDir = imageFs.getRootDir();
        File tmpDir = XEnvironment.getTmpDir(context);
        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
        File nativeLibs = new File(nativeLibraryDir);
        // Log.d("GuestProgramLauncherComponent", nativeLibraryDir + " exists: " + nativeLibs.exists());
        // Log.d("GuestProgramLauncherComponent", nativeLibraryDir + " is directory: " + nativeLibs.isDirectory());
        Log.d("GuestProgramLauncherComponent", nativeLibraryDir + " contains: " + Arrays.toString(Arrays.stream(nativeLibs.listFiles()).map(File::getName).toArray()));
        // nativeLibraryDir = nativeLibraryDir.replace("arm64", "arm64-v8a");
        // Log.d("GuestProgramLauncherComponent", nativeLibraryDir + " exists: " + (new File(nativeLibraryDir)).exists());
        // Log.d("GuestProgramLauncherComponent", steamApiPath + " exists: " + new File(steamApiPath).exists());
        // ImageFs fs = ImageFs.find(context);
        // Path dllsDir = Paths.get(fs.getRootDir().getAbsolutePath(), "/usr/dlls");
        // Path steamApiTargetPath = Paths.get(dllsDir.toString(), "steam_api.dll.so");
        // Path steamApiTargetPath = Paths.get(fs.getLib64Dir().toString(), "libsteam_api.dll.so");
        // try {
        //     if (Files.exists(steamApiTargetPath)) {
        //         Files.delete(steamApiTargetPath);
        //     }
        //     // Files.createDirectories(dllsDir);
        //     Path steamApiPath = Paths.get(nativeLibraryDir, "libsteam_api.dll.so");
        //     Files.copy(steamApiPath, steamApiTargetPath);
        //     FileUtils.chmod(new File(steamApiTargetPath.toString()), 0771);
        // } catch (IOException e) {
        //     Log.e("GuestProgramLauncherComponent", "Failed to copy steam_api.dll.so to /usr/lib " + e);
        // }

        // PrefManager.init(context);
        // boolean enableBox86_64Logs = PrefManager.getBoolean("enable_box86_64_logs", false);

        EnvVars envVars = new EnvVars();
        // if (!wow64Mode) addBox86EnvVars(envVars, enableBox86_64Logs);
        // addBox64EnvVars(envVars, enableBox86_64Logs);

        TimeZone androidTz = TimeZone.getDefault();
        String tzId = androidTz.getID();
        // Log.d("GuestProgramLauncherComponent", "Android timezone: " + tzId);

        envVars.put("TZ", tzId);
        envVars.put("HOME", ImageFs.HOME_PATH);
        envVars.put("USER", ImageFs.USER);
        envVars.put("TMPDIR", "/tmp");
        envVars.put("LC_ALL", "en_US.utf8");
        envVars.put("DISPLAY", ":0");
        envVars.put("PATH", imageFs.getWinePath() + "/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        envVars.put("LD_LIBRARY_PATH", "/usr/lib/aarch64-linux-gnu:/usr/lib/arm-linux-gnueabihf");
        envVars.put("ANDROID_SYSVSHM_SERVER", UnixSocketConfig.SYSVSHM_SERVER_PATH);

        if ((new File(imageFs.getLib64Dir(), "libandroid-sysvshm.so")).exists() ||
                (new File(imageFs.getLib32Dir(), "libandroid-sysvshm.so")).exists())
            envVars.put("LD_PRELOAD", "libandroid-sysvshm.so");
        if (extraVars != null) envVars.putAll(extraVars);

        boolean bindSHM = envVars.get("WINEESYNC").equals("1");

        String command = nativeLibraryDir + "/libproot.so";
        // Log.d("GuestProgramLauncherComponent", nativeLibraryDir + "/libproot.so exists: " + (new File(nativeLibraryDir + "/libproot.so")).exists());
        command += " --kill-on-exit";
        command += " --rootfs=" + rootDir;
        command += " --cwd=" + ImageFs.HOME_PATH;
        command += " --bind=/dev";

        if (bindSHM) {
            File shmDir = new File(rootDir, "/tmp/shm");
            shmDir.mkdirs();
            command += " --bind=" + shmDir.getAbsolutePath() + ":/dev/shm";
        }

        command += " --bind=/proc";
        command += " --bind=/sys";

        if (bindingPaths != null) {
            for (String path : bindingPaths)
                command += " --bind=\"" + (new File(path)).getAbsolutePath() + "\"";
        }

        // envVars.put("WINEDLLPATH", dllsDir.toString());
        // envVars.put("WINEDLLOVERRIDES", "\"steam_api=n\"");
        envVars.put("WINEESYNC", "0");

        command += " /usr/bin/env " + envVars.toEscapedString() + " " + prootCmd;

        envVars.clear();
        envVars.put("PROOT_TMP_DIR", tmpDir);
        envVars.put("PROOT_LOADER", nativeLibraryDir + "/libproot-loader.so");
        if (proot32) envVars.put("PROOT_LOADER_32", nativeLibraryDir + "/libproot-loader32.so");

        // ProcessHelper.exec(nativeLibraryDir+"/libproot.so ulimit -a", envVars.toStringArray(), rootDir);
        return ProcessHelper.exec(command, envVars.toStringArray(), workingDir != null ? workingDir : rootDir, (status) -> {
            Log.d("GuestProgramLauncherComponent", "Process terminated " + pid + " with status " + status);
            synchronized (lock) {
                pid = -1;
            }
            if (terminationCallback != null) terminationCallback.call(status);
        });
    }

    private void extractBox86_64Files() {
        ImageFs imageFs = environment.getImageFs();
        Context context = environment.getContext();
        PrefManager.init(context);
        String currentBox86Version = PrefManager.getString("current_box86_version", "");
        String currentBox64Version = PrefManager.getString("current_box64_version", "");
        File rootDir = imageFs.getRootDir();

        if (wow64Mode) {
            File box86File = new File(rootDir, "/usr/local/bin/box86");
            if (box86File.isFile()) {
                box86File.delete();
                PrefManager.putString("current_box86_version", "");
            }
        } else if (!box86Version.equals(currentBox86Version)) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.getAssets(), "box86_64/box86-" + box86Version + ".tzst", rootDir);
            PrefManager.putString("current_box86_version", box86Version);
        }

        if (!box64Version.equals(currentBox64Version)) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.getAssets(), "box86_64/box64-" + box64Version + ".tzst", rootDir);
            PrefManager.putString("current_box64_version", box64Version);
        }
    }

    void copyDefaultBox64RCFile() {
        Context context = environment.getContext();
        ImageFs imageFs = ImageFs.find(context);
        File rootDir = imageFs.getRootDir();
        String assetPath;
        switch (steamType) {
            case Container.STEAM_TYPE_LIGHT:
                assetPath = "box86_64/lightsteam.box64rc";
                break;
            case Container.STEAM_TYPE_ULTRALIGHT:
                assetPath = "box86_64/ultralightsteam.box64rc";
                break;
            default:
                assetPath = "box86_64/default.box64rc";
                break;
        }
        FileUtils.copy(context, assetPath, new File(rootDir, "/etc/config.box64rc"));
    }

    private void addBox86EnvVars(EnvVars envVars, boolean enableLogs) {
        envVars.put("BOX86_NOBANNER", ProcessHelper.PRINT_DEBUG && enableLogs ? "0" : "1");
        envVars.put("BOX86_DYNAREC", "1");

        if (enableLogs) {
            envVars.put("BOX86_LOG", "1");
            envVars.put("BOX86_DYNAREC_MISSING", "1");
        }

        envVars.putAll(Box86_64PresetManager.getEnvVars("box86", environment.getContext(), box86Preset));
        envVars.put("BOX86_X11GLX", "1");
        envVars.put("BOX86_NORCFILES", "1");
    }

    private void addBox64EnvVars(EnvVars envVars, boolean enableLogs) {
        envVars.put("BOX64_NOBANNER", ProcessHelper.PRINT_DEBUG && enableLogs ? "0" : "1");
        envVars.put("BOX64_DYNAREC", "1");
        if (wow64Mode) envVars.put("BOX64_MMAP32", "1");
        envVars.put("BOX64_AVX", "1");

        if (enableLogs) {
            envVars.put("BOX64_LOG", "1");
            envVars.put("BOX64_DYNAREC_MISSING", "1");
        }

        envVars.putAll(Box86_64PresetManager.getEnvVars("box64", environment.getContext(), box64Preset));
        envVars.put("BOX64_X11GLX", "1");
        envVars.put("BOX64_NORCFILES", "1");
    }

    public void suspendProcess() {
        ProcessHelper.pauseAllWineProcesses();
    }

    public void resumeProcess() {
        ProcessHelper.resumeAllWineProcesses();
    }

    public String execShellCommand(String command){
        return execShellCommand(command, true);
    }

    public String execShellCommand(String command, boolean includeStderr){
        return "";
    }
}
