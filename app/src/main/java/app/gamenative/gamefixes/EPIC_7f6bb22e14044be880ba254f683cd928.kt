package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container
import com.winlator.core.FileUtils
import com.winlator.core.KeyValueSet
import com.winlator.core.TarCompressorUtils
import com.winlator.core.envvars.EnvVars
import timber.log.Timber
import java.io.File

/**
 * Trover Saves the Universe (Epic - Sweetpea)
 *
 * - Injects GameDefaultMap=/Game/Squanch/LobbyLevel/Lobby_master into Engine.ini to load the main 3D lobby level directly, bypassing the video player map.
 * - Injects r.SceneColorFormat=2 (PF_A8R8G8B8 8-bit integer color target) to prevent Turnip Vulkan black screen rendering.
 * - Injects WINEPATH=A:\Trover\Binaries\Win64;A:\ so Wine's DLL loader finds Trover's 64-bit DLLs in its subfolder directory.
 * - Disables VR plugins (SteamVR, OculusVR, OculusAudio) and sets VR system variables (vr.InstancedStereo=0, vr.MultiView=0, -nohmd).
 * - Extracts 64-bit DXVK (dxvk-async-1.10.3) directly into Trover/Binaries/Win64/.
 * - Extracts 64-bit wldap32.dll for LDAP stability.
 * - Configures container envVars (BOX64_AVX=1, BOX64_MMAP32=0, Turnip Vulkan driver, OPENSSL_ia32cap).
 * - Ensures Epic offline mode is false for LauncherCheck DRM authentication.
 */
val EPIC_Fix_7f6bb22e14044be880ba254f683cd928: KeyedGameFix = object : KeyedGameFix {
    override val gameSource = GameSource.EPIC
    override val gameId = "7f6bb22e14044be880ba254f683cd928"

    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        return try {
            var changed = false

            if (container.containerVariant == Container.BIONIC) {
                if (container.box64Version != "0.4.2") {
                    container.box64Version = "0.4.2"
                    changed = true
                }
                if (container.wineVersion.isEmpty() || container.wineVersion.contains("arm64ec")) {
                    container.wineVersion = "proton-9.0-x86_64"
                    changed = true
                }
            }

            if (container.graphicsDriver != "turnip") {
                container.graphicsDriver = "turnip"
                container.graphicsDriverVersion = "25.1.0"
                changed = true
            }

            // Force dxvk-async-1.10.3 to avoid Mesa Turnip GPL pipeline bugs in DXVK 2.x
            if (container.dxWrapper != "dxvk-async-1.10.3") {
                container.dxWrapper = "dxvk-async-1.10.3"
                changed = true
            }

            val dxConfig = KeyValueSet(container.dxWrapperConfig)
            if (dxConfig.get("gpuName") != null && dxConfig.get("gpuName").isNotEmpty()) {
                dxConfig.put("gpuName", "")
                container.dxWrapperConfig = dxConfig.toString()
                changed = true
            }

            val configDir = File(container.getRootDir(), "home/xuser/.config")
            if (!configDir.exists()) configDir.mkdirs()
            val dxvkConf = File(configDir, "dxvk.conf")
            val dxvkConfContent = "dxgi.nvapiHack = False\ndxvk.enableAsync = True\ndxgi.syncInterval = 0\n"
            if (!dxvkConf.exists() || dxvkConf.readText() != dxvkConfContent) {
                try {
                    dxvkConf.writeText(dxvkConfContent)
                    changed = true
                } catch (e: Exception) {
                    Timber.tag("GameFixes").e(e, "Failed to write dxvk.conf")
                }
            }

            val envVars = EnvVars(container.envVars)
            if (!envVars.has("BOX64_AVX") || envVars.get("BOX64_AVX") != "1") {
                envVars.put("BOX64_AVX", "1")
                changed = true
            }

            if (!envVars.has("BOX64_MMAP32") || envVars.get("BOX64_MMAP32") != "0") {
                envVars.put("BOX64_MMAP32", "0")
                changed = true
            }

            if (envVars.has("BOX64_EMULATED_LIBS") && envVars.get("BOX64_EMULATED_LIBS").isNotEmpty()) {
                envVars.put("BOX64_EMULATED_LIBS", "")
                changed = true
            }

            if (envVars.has("MESA_VK_WSI_PRESENT_MODE")) {
                envVars.remove("MESA_VK_WSI_PRESENT_MODE")
                changed = true
            }

            if (!envVars.has("OPENSSL_ia32cap") || envVars.get("OPENSSL_ia32cap") != "~0x200000200000000") {
                envVars.put("OPENSSL_ia32cap", "~0x200000200000000")
                changed = true
            }

            // Injects WINEPATH so Wine DLL loader searches Trover's binary directory for DLL dependencies
            if (!envVars.has("WINEPATH") || envVars.get("WINEPATH") != "A:\\Trover\\Binaries\\Win64;A:\\") {
                envVars.put("WINEPATH", "A:\\Trover\\Binaries\\Win64;A:\\")
                changed = true
            }

            // Restore any previously disabled VR DLLs if they exist
            val rootInstallDir = File(installPath)
            if (rootInstallDir.exists()) {
                rootInstallDir.walkTopDown().forEach { file ->
                    if (file.isFile && file.name.endsWith(".disabled")) {
                        val restoredFile = File(file.parentFile, file.name.removeSuffix(".disabled"))
                        if (file.renameTo(restoredFile)) {
                            changed = true
                        }
                    }
                }
            }

            // Search AppData directories safely without symlink loops
            val appDataDirs = mutableListOf<File>(
                File(container.getRootDir(), "home/xuser/.wine/drive_c/users/xuser/AppData/Local/Trover/Saved/Config/WindowsNoEditor"),
                File(container.getRootDir(), "home/xuser/.wine/drive_c/users/xuser/Local Settings/Application Data/Trover/Saved/Config/WindowsNoEditor")
            )

            val sharedDir = File(context.filesDir, "imagefs_shared/home")
            if (sharedDir.exists()) {
                sharedDir.listFiles()?.forEach { userHome ->
                    if (userHome.isDirectory) {
                        val configDir1 = File(userHome, ".wine/drive_c/users/xuser/AppData/Local/Trover/Saved/Config/WindowsNoEditor")
                        val configDir2 = File(userHome, ".wine/drive_c/users/xuser/Local Settings/Application Data/Trover/Saved/Config/WindowsNoEditor")
                        if (!appDataDirs.contains(configDir1)) appDataDirs.add(configDir1)
                        if (!appDataDirs.contains(configDir2)) appDataDirs.add(configDir2)
                    }
                }
            }

            val engineIniContent = """
                [/Script/EngineSettings.GameMapsSettings]
                ServerDefaultMap=/Game/Squanch/LobbyLevel/Lobby_master
                GameDefaultMap=/Game/Squanch/LobbyLevel/Lobby_master

                [Plugins]
                DisabledPlugins="SteamVR"
                DisabledPlugins="SteamVRInput"
                DisabledPlugins="OculusVR"
                DisabledPlugins="OculusAudio"
                DisabledPlugins="OnlineSubsystemOculus"
                DisabledPlugins="WmfMedia"

                [/Script/MoviePlayer.MoviePlayerSettings]
                bWaitForMoviesToComplete=False
                bMoviesAreSkippable=True
                !StartupMovies=ClearArray

                [SystemSettings]
                vr.HeadMountedDisplay.Supported=False
                r.HMDEnable=0
                vr.InstancedStereo=0
                vr.MultiView=0
                vr.SteamVR.EnableVRInput=0
                r.HDR.EnableHDROutput=0
                r.DefaultFeature.HDRUI=0
                r.SceneColorFormat=2
                r.AllowHDR=0
                r.ShadowQuality=1
                r.Shadow.CSM.MaxCascades=1
                r.Shadow.MaxResolution=512
                r.VolumetricFog=0
                r.BloomQuality=1
                r.MotionBlurQuality=0
                r.DepthOfFieldQuality=0
                r.LightShaftQuality=0
                r.SSR.Quality=0

                [/Script/Engine.Engine]
                bAllowStereoSettings=False
            """.trimIndent()

            val scalabilityIniContent = """
                [ScalabilitySettings]
                r.ShadowQuality=1
                r.Shadow.CSM.MaxCascades=1
                r.Shadow.MaxResolution=512
                r.VolumetricFog=0
                r.BloomQuality=1
                r.MotionBlurQuality=0
                r.DepthOfFieldQuality=0
                r.LightShaftQuality=0
                r.SSR.Quality=0

                [ShadowQuality@0]
                r.ShadowQuality=1
                r.Shadow.CSM.MaxCascades=1
                r.Shadow.MaxResolution=512

                [ShadowQuality@1]
                r.ShadowQuality=1
                r.Shadow.CSM.MaxCascades=1
                r.Shadow.MaxResolution=512

                [ShadowQuality@2]
                r.ShadowQuality=1
                r.Shadow.CSM.MaxCascades=1
                r.Shadow.MaxResolution=512

                [ShadowQuality@3]
                r.ShadowQuality=1
                r.Shadow.CSM.MaxCascades=1
                r.Shadow.MaxResolution=512
            """.trimIndent()

            val gameUserSettingsContent = """
                [ScalabilityGroups]
                sg.ResolutionQuality=100.000000
                sg.ViewDistanceQuality=1
                sg.AntiAliasingQuality=1
                sg.ShadowQuality=1
                sg.PostProcessQuality=1
                sg.TextureQuality=1
                sg.EffectsQuality=1
                sg.FoliageQuality=1

                [/Script/Engine.GameUserSettings]
                bUseVSync=False
                ResolutionSizeX=1280
                ResolutionSizeY=720
                LastUserConfirmedResolutionSizeX=1280
                LastUserConfirmedResolutionSizeY=720
                WindowMode=1
            """.trimIndent()

            for (appDataDir in appDataDirs) {
                if (!appDataDir.exists()) appDataDir.mkdirs()
                val engineIni = File(appDataDir, "Engine.ini")
                if (engineIni.exists()) FileUtils.chmod(engineIni, 438)
                try {
                    engineIni.writeText(engineIniContent)
                    changed = true
                } catch (e: Exception) {
                    Timber.tag("GameFixes").e(e, "Failed to write Engine.ini at ${engineIni.path}")
                }

                val gameIni = File(appDataDir, "Game.ini")
                if (gameIni.exists()) {
                    try {
                        gameIni.delete()
                        changed = true
                    } catch (e: Exception) {
                        Timber.tag("GameFixes").e(e, "Failed to delete Game.ini at ${gameIni.path}")
                    }
                }

                val scalabilityIni = File(appDataDir, "Scalability.ini")
                if (scalabilityIni.exists()) FileUtils.chmod(scalabilityIni, 438)
                try {
                    scalabilityIni.writeText(scalabilityIniContent)
                    changed = true
                } catch (e: Exception) {
                    Timber.tag("GameFixes").e(e, "Failed to write Scalability.ini at ${scalabilityIni.path}")
                }

                val gameUserSettingsIni = File(appDataDir, "GameUserSettings.ini")
                if (gameUserSettingsIni.exists()) FileUtils.chmod(gameUserSettingsIni, 438)
                try {
                    gameUserSettingsIni.writeText(gameUserSettingsContent)
                    changed = true
                } catch (e: Exception) {
                    Timber.tag("GameFixes").e(e, "Failed to write GameUserSettings.ini at ${gameUserSettingsIni.path}")
                }
            }

            val win64Dir = File(installPath, "Trover/Binaries/Win64")
            if (win64Dir.exists()) {
                val dxvkTmpDir = File(context.cacheDir, "dxvk_trover_tmp")
                if (dxvkTmpDir.exists()) FileUtils.delete(dxvkTmpDir)
                dxvkTmpDir.mkdirs()
                try {
                    TarCompressorUtils.extract(
                        TarCompressorUtils.Type.ZSTD,
                        context.assets,
                        "dxwrapper/dxvk-async-1.10.3.tzst",
                        dxvkTmpDir
                    )
                    val x64Dir = File(dxvkTmpDir, "x64")
                    val sourceDir = if (x64Dir.exists()) x64Dir else dxvkTmpDir
                    listOf("d3d11.dll", "dxgi.dll", "d3d10core.dll", "d3d12.dll").forEach { dllName ->
                        val srcDll = File(sourceDir, dllName)
                        val dstDll = File(win64Dir, dllName)
                        if (srcDll.exists()) {
                            srcDll.copyTo(dstDll, overwrite = true)
                            changed = true
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag("GameFixes").e(e, "Failed to extract DXVK directly to win64Dir")
                } finally {
                    FileUtils.delete(dxvkTmpDir)
                }
            }

            if (win64Dir.exists()) {
                val gameWldap32 = File(win64Dir, "wldap32.dll")
                if (!gameWldap32.exists()) {
                    try {
                        FileUtils.copy(context, "sys_dlls/wldap32.dll", gameWldap32)
                        FileUtils.chmod(gameWldap32, 493)
                        changed = true
                    } catch (e: Exception) {
                        Timber.tag("GameFixes").e(e, "Failed to extract wldap32.dll to game directory")
                    }
                }
            }

            val system32Dir = File(container.getRootDir(), "home/xuser/.wine/drive_c/windows/system32")
            if (system32Dir.exists()) {
                val sysWldap32 = File(system32Dir, "wldap32.dll")
                if (!sysWldap32.exists()) {
                    try {
                        FileUtils.copy(context, "sys_dlls/wldap32.dll", sysWldap32)
                        FileUtils.chmod(sysWldap32, 493)
                        changed = true
                    } catch (e: Exception) {
                        Timber.tag("GameFixes").e(e, "Failed to extract wldap32.dll to system32")
                    }
                }
            }

            val dllOverrides = envVars.get("WINEDLLOVERRIDES")
            val cleanOverrides = dllOverrides.split(";")
                .filter {
                    !it.contains("d3d12=") && !it.contains("d3d11=") && !it.contains("dxgi=") && !it.contains("d3d10core=") && !it.contains("d3d9=") &&
                    !it.contains("WLDAP32=") && !it.contains("wldap32=") && !it.contains("ntdsapi=") &&
                    !it.contains("crypt32=") && !it.contains("bcrypt=") &&
                    !it.contains("mf=") && !it.contains("mfplat=") && !it.contains("mfmediaengine=") && !it.contains("secur32=") && !it.contains("gnutls=") &&
                    !it.contains("OVRPlugin=") && !it.contains("openvr_api=") && !it.contains("kerberos=") &&
                    !it.contains("EOSSDK=") && !it.contains("EOSSDK-Win64-Shipping=") &&
                    it.isNotBlank()
                }
                .toMutableList()

            val targetOverrides = "d3d12=n,b;d3d11=n,b;dxgi=n,b;d3d10core=n,b;d3d9=n,b;secur32=b;wldap32=b;ntdsapi=b;crypt32=b;bcrypt=b;OVRPlugin=b,n;openvr_api=b,n;kerberos=d"
            cleanOverrides.add(targetOverrides)

            val finalOverrides = cleanOverrides.joinToString(";")
            if (dllOverrides != finalOverrides) {
                envVars.put("WINEDLLOVERRIDES", finalOverrides)
                changed = true
            }

            if (changed) {
                container.envVars = envVars.toString()
            }

            if (container.isEpicOfflineMode) {
                container.setEpicOfflineMode(false)
                changed = true
            }

            val currentArgs = container.execArgs ?: ""
            var cleanArgs = currentArgs.replace("-vrmode none", "")
                .replace("-vrmode", "")
                .replace("none", "")
                .replace("-novr", "")
                .replace("-noborder", "")
                .replace("/Game/Squanch/LobbyLevel/Lobby_master", "")

            val requiredUE4Args = listOf(
                "-nohmd",
                "-windowed",
                "-ResX=1280",
                "-ResY=720",
                "-dx11",
                "-vr.SteamVR.EnableVRInput=0",
                "-vr.InstancedStereo=0",
                "-vr.MultiView=0"
            )

            val argList = cleanArgs.split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
            var argsChanged = false
            for (req in requiredUE4Args) {
                if (!argList.contains(req)) {
                    argList.add(req)
                    argsChanged = true
                }
            }

            if (argsChanged || cleanArgs != currentArgs) {
                container.execArgs = argList.joinToString(" ")
                changed = true
            }

            if (changed) {
                container.saveData()
                Timber.tag("GameFixes").i("Applied Trover Saves the Universe fix")
            }
            true
        } catch (e: Exception) {
            Timber.tag("GameFixes").e(e, "Failed to apply Trover Saves the Universe fix")
            false
        }
    }
}
