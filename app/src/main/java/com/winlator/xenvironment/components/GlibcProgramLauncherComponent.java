package com.winlator.xenvironment.components;

import static com.winlator.core.ProcessHelper.splitCommand;

import android.content.Context;
import android.media.Image;
import android.os.Process;
import android.util.Log;

import com.winlator.PrefManager;
import com.winlator.box86_64.Box86_64Preset;
import com.winlator.box86_64.Box86_64PresetManager;
import com.winlator.contents.ContentProfile;
import com.winlator.contents.ContentsManager;
import com.winlator.core.Callback;
import com.winlator.core.DefaultVersion;
import com.winlator.core.FileUtils;
import com.winlator.core.envvars.EnvVars;
import com.winlator.core.ProcessHelper;
import com.winlator.core.TarCompressorUtils;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.ImageFs;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;

public class GlibcProgramLauncherComponent extends GuestProgramLauncherComponent {
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
    private final ContentsManager contentsManager;
    private final ContentProfile wineProfile;
    private File workingDir;

    public GlibcProgramLauncherComponent(ContentsManager contentsManager, ContentProfile wineProfile) {
        this.contentsManager = contentsManager;
        this.wineProfile = wineProfile;
    }

    private Runnable preUnpack;
    public void setPreUnpack(Runnable r) { this.preUnpack = r; }
    @Override
    public void start() {
        Log.d("GlibcProgramLauncherComponent", "Starting...");

        // Log targetSdk version and related info
        Context context = environment.getContext();
        try {
            int targetSdk = context.getApplicationInfo().targetSdkVersion;
            int compileSdk = android.os.Build.VERSION_CODES.CUR_DEVELOPMENT;
            int deviceSdk = android.os.Build.VERSION.SDK_INT;

            Log.d("GlibcProgramLauncherComponent", "App targetSdk: " + targetSdk);
            Log.d("GlibcProgramLauncherComponent", "Device SDK: " + deviceSdk + " (" + android.os.Build.VERSION.RELEASE + ")");
            Log.d("GlibcProgramLauncherComponent", "Package name: " + context.getPackageName());

        } catch (Exception e) {
            Log.e("GlibcProgramLauncherComponent", "Error getting SDK info: " + e.getMessage());
        }

        synchronized (lock) {
            stop();
            extractBox86_64Files();
            copyDefaultBox64RCFile();
            if (preUnpack != null) preUnpack.run();
            pid = execGuestProgram();
            Log.d("GlibcProgramLauncherComponent", "Process " + pid + " started");
        }
    }

    @Override
    public void stop() {
        Log.d("GlibcProgramLauncherComponent", "Stopping...");
        synchronized (lock) {
            if (pid != -1) {
                Process.killProcess(pid);
                Log.d("GlibcProgramLauncherComponent", "Stopped process " + pid);
                pid = -1;
                List<ProcessHelper.ProcessInfo> subProcesses = ProcessHelper.listSubProcesses();
                for (ProcessHelper.ProcessInfo subProcess : subProcesses) {
                    Log.d("GlibcProgramLauncherComponent",
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
        ImageFs imageFs = ImageFs.find(context);
        File rootDir = imageFs.getRootDir();

        PrefManager.init(context);
        boolean enableBox86_64Logs = PrefManager.getBoolean("enable_box86_64_logs", true);

        EnvVars envVars = new EnvVars();
        if (!wow64Mode) addBox86EnvVars(envVars, enableBox86_64Logs);
        addBox64EnvVars(envVars, enableBox86_64Logs);
        envVars.put("HOME", imageFs.home_path);
        envVars.put("USER", ImageFs.USER);
        envVars.put("TMPDIR", imageFs.getRootDir().getPath() + "/tmp");
        envVars.put("DISPLAY", ":0");

        String winePath = wineProfile == null ? imageFs.getWinePath() + "/bin"
                : ContentsManager.getSourceFile(context, wineProfile, wineProfile.wineBinPath).getAbsolutePath();
        envVars.put("PATH", winePath + ":" +
                imageFs.getRootDir().getPath() + "/usr/bin:" +
                imageFs.getRootDir().getPath() + "/usr/local/bin");

        envVars.put("LD_LIBRARY_PATH", imageFs.getRootDir().getPath() + "/usr/lib");
        envVars.put("BOX64_LD_LIBRARY_PATH", imageFs.getRootDir().getPath() + "/usr/lib/x86_64-linux-gnu");
        envVars.put("ANDROID_SYSVSHM_SERVER", imageFs.getRootDir().getPath() + UnixSocketConfig.SYSVSHM_SERVER_PATH);
        envVars.put("FONTCONFIG_PATH", imageFs.getRootDir().getPath() + "/usr/etc/fonts");

        if ((new File(imageFs.getGlibc64Dir(), "libandroid-sysvshm.so")).exists() ||
                (new File(imageFs.getGlibc32Dir(), "libandroid-sysvshm.so")).exists
                        ())
            envVars.put("LD_PRELOAD", "libredirect.so libandroid-sysvshm.so");
        envVars.put("WINEESYNC_WINLATOR", "1");
        if (this.envVars != null) envVars.putAll(this.envVars);

        String box64Path = rootDir.getPath() + "/usr/local/bin/box64";

        // Check if box64 exists and log its details before executing
        File box64File = new File(box64Path);
        Log.d("GlibcProgramLauncherComponent", "About to execute box64 from: " + box64Path);

        String command = box64Path + " " + guestExecutable;
        Log.d("GlibcProgramLauncherComponent", "Final command: " + command);

        return ProcessHelper.exec(command, envVars.toStringArray(), workingDir != null ? workingDir : rootDir, (status) -> {
            Log.d("GlibcProgramLauncherComponent", "Process terminated " + pid + " with status " + status);
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

        Log.d("GlibcProgramLauncherComponent", "Extracting box86/64 files to rootDir: " + rootDir.getAbsolutePath());

        if (wow64Mode) {
            Log.d("GlibcProgramLauncherComponent", "wow64mode");
            File box86File = new File(rootDir, "/usr/local/bin/box86");
            if (box86File.isFile()) {
                box86File.delete();
                PrefManager.putString("current_box86_version", "");
            }
        } else if (!box86Version.equals(currentBox86Version)) {
            Log.d("GlibcProgramLauncherComponent", "Extracting box86 version " + box86Version + " (current version: " + currentBox86Version + ")");
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.getAssets(), "box86_64/box86-" + box86Version + ".tzst", rootDir);
            PrefManager.putString("current_box86_version", box86Version);
        }

        Log.d("GlibcProgramLauncherComponent", "box64Version " + box64Version);
        Log.d("GlibcProgramLauncherComponent", "currentBox64Version " + currentBox64Version);

        if (!box64Version.equals(currentBox64Version)) {
            ContentProfile profile = contentsManager.getProfileByEntryName("box64-" + box64Version);
            if (profile != null) {
                Log.d("GlibcProgramLauncherComponent", "Profile is not null - applying content for box64 version " + box64Version);
                contentsManager.applyContent(profile);
            }
            else {
                Log.d("GlibcProgramLauncherComponent", "Profile is null - extracting box64 version " + box64Version + " directly");
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.getAssets(), "box86_64/box64-" + box64Version + ".tzst", rootDir);
            }
            PrefManager.putString("current_box64_version", box64Version);
        }
    }

    private void copyDefaultBox64RCFile() {
        Context context = environment.getContext();
        ImageFs imageFs = ImageFs.find(context);
        File rootDir = imageFs.getRootDir();
        FileUtils.copy(context, "box86_64/default.box64rc", new File(rootDir, "/etc/config.box64rc"));
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
        Context context = environment.getContext();
        ImageFs imageFs = ImageFs.find(context);
        envVars.put("BOX64_NOBANNER", ProcessHelper.PRINT_DEBUG && enableLogs ? "0" : "1");
        envVars.put("BOX64_DYNAREC", "1");
        if (wow64Mode) envVars.put("BOX64_MMAP32", "1");

        if (enableLogs) {
            envVars.put("BOX64_LOG", "1");
            envVars.put("BOX64_DYNAREC_MISSING", "1");
        }

        envVars.putAll(Box86_64PresetManager.getEnvVars("box64", environment.getContext(), box64Preset));
        envVars.put("BOX64_X11GLX", "1");
        File box64RCFile = new File(imageFs.getRootDir(), "/etc/config.box64rc");
        envVars.put("BOX64_RCFILE", box64RCFile.getPath());
    }

    public void suspendProcess() {
        synchronized (lock) {
            if (pid != -1) ProcessHelper.suspendProcess(pid);
        }
    }

    public void resumeProcess() {
        synchronized (lock) {
            if (pid != -1) ProcessHelper.resumeProcess(pid);
        }
    }

    public String execShellCommand(String command) {
        Context context = environment.getContext();
        ImageFs imageFs = ImageFs.find(context);
        File rootDir = imageFs.getRootDir();

        PrefManager.init(context);
        StringBuilder output = new StringBuilder();
        EnvVars envVars = new EnvVars();
        envVars.put("HOME", imageFs.home_path);
        envVars.put("USER", ImageFs.USER);
        envVars.put("TMPDIR", imageFs.getRootDir().getPath() + "/tmp");
        envVars.put("DISPLAY", ":0");

        String winePath = wineProfile == null ? imageFs.getWinePath() + "/bin"
                : ContentsManager.getSourceFile(context, wineProfile, wineProfile.wineBinPath).getAbsolutePath();
        envVars.put("PATH", winePath + ":" +
                imageFs.getRootDir().getPath() + "/usr/bin:" +
                imageFs.getRootDir().getPath() + "/usr/local/bin");

        envVars.put("LD_LIBRARY_PATH", imageFs.getRootDir().getPath() + "/usr/lib");
        envVars.put("BOX64_LD_LIBRARY_PATH", imageFs.getRootDir().getPath() + "/usr/lib/x86_64-linux-gnu");
        envVars.put("ANDROID_SYSVSHM_SERVER", imageFs.getRootDir().getPath() + UnixSocketConfig.SYSVSHM_SERVER_PATH);
        envVars.put("FONTCONFIG_PATH", imageFs.getRootDir().getPath() + "/usr/etc/fonts");

        if ((new File(imageFs.getGlibc64Dir(), "libandroid-sysvshm.so")).exists() ||
                (new File(imageFs.getGlibc32Dir(), "libandroid-sysvshm.so")).exists
                        ())
            envVars.put("LD_PRELOAD", "libredirect.so libandroid-sysvshm.so");
        envVars.put("WINEESYNC_WINLATOR", "1");
        if (this.envVars != null) envVars.putAll(this.envVars);

        String box64Path = rootDir.getPath() + "/usr/local/bin/box64";

        String finalCommand = box64Path + " " + command;

        // Execute the command and capture its output
        try {
            Log.d("GlibcProgramLauncherComponent", "Shell command is " + finalCommand);
            java.lang.Process process = Runtime.getRuntime().exec(finalCommand, envVars.toStringArray(), workingDir != null ? workingDir : imageFs.getRootDir());
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            while ((line = errorReader.readLine()) != null) {
                output.append(line).append("\n");
            }
            process.waitFor();
        } catch (Exception e) {
            output.append("Error: ").append(e.getMessage());
        }

        return output.toString();
    }
}
