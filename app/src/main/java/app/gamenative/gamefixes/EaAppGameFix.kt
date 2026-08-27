package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container
import com.winlator.core.WineRegistryEditor
import java.io.File
import timber.log.Timber

private val EA_SOFTWARE_RENDERER_EXES = listOf(
    "EACefSubProcess.exe",
)
private val EA_QT_HOST_EXES = listOf("EAappInstaller.exe", "EADesktop.exe", "EALaunchHelper.exe")
private val EA_SOFTWARE_RENDERER_DLLS = listOf("dxgi", "d3d11", "d3d9")

private const val EA_DESKTOP_INSTALL_ROOT =
    ".wine/drive_c/Program Files/Electronic Arts/EA Desktop"
private const val EA_INSTALL_SUCCESS_KEY_UPPERCASE =
    "HKEY_LOCAL_MACHINE\\\\SOFTWARE\\\\Electronic Arts\\\\EA Desktop\\\\InstallSuccessful"
private const val EA_INSTALL_SUCCESS_KEY_WINE_CASE =
    "HKEY_LOCAL_MACHINE\\\\Software\\\\Electronic Arts\\\\EA Desktop\\\\InstallSuccessful"
private const val EA_INSTALLER_PROCESS =
    "%INSTALLDIR%\\\\__Installer\\\\Origin\\\\redist\\\\internal\\\\EAappInstaller.exe"
private const val EA_HEADLESS_INSTALLER_PROCESS =
    "%INSTALLDIR%\\\\__Installer\\\\Origin\\\\redist\\\\internal\\\\EAapp-wine-install.cmd"
private const val EA_HEADLESS_INSTALLER_RELATIVE_PATH =
    "__Installer/Origin/redist/internal/EAapp-wine-install.cmd"
private const val EA_HEADLESS_MSI_RELATIVE_PATH =
    "__Installer/Origin/redist/internal/EAapp-wine-no-start.msi"

private fun configureEaInstallScript(installPath: String) {
    val installScript = File(installPath, "installscript.vdf")
    if (!installScript.isFile) return

    val contents = installScript.readText()
    var configured = contents.replace(
        EA_INSTALL_SUCCESS_KEY_UPPERCASE,
        EA_INSTALL_SUCCESS_KEY_WINE_CASE,
    )
    if (
        File(installPath, EA_HEADLESS_INSTALLER_RELATIVE_PATH).isFile &&
        File(installPath, EA_HEADLESS_MSI_RELATIVE_PATH).isFile
    ) {
        configured = configured.replace(EA_INSTALLER_PROCESS, EA_HEADLESS_INSTALLER_PROCESS)
    }
    if (configured != contents) installScript.writeText(configured)
}

private fun findInstalledEaDesktop(container: Container): Pair<String, String>? {
    val installRoot = File(container.rootDir, EA_DESKTOP_INSTALL_ROOT)
    val versionDir = installRoot.listFiles()
        ?.filter { it.isDirectory && File(it, "EA Desktop/EADesktop.exe").isFile }
        ?.maxByOrNull { dir ->
            dir.name.split('.').fold(0L) { value, component ->
                value * 10_000L + (component.toLongOrNull() ?: 0L)
            }
        }
        ?: return null

    val version = versionDir.name
    val basePath = "C:\\Program Files\\Electronic Arts\\EA Desktop\\$version"
    return version to "$basePath\\EA Desktop"
}

internal fun applyEaCompatibilityRegistry(container: Container, gameExeWindowsPath: String?) {
    val (version, eaDesktopDir) = findInstalledEaDesktop(container) ?: return
    val desktopPath = "$eaDesktopDir\\EADesktop.exe"
    val launcherPath = "$eaDesktopDir\\EALauncher.exe"
    val link2EaPath = "$eaDesktopDir\\Link2EA.exe"
    val steamPath = "C:\\Program Files (x86)\\Steam\\steam.exe"
    val useLegacySteamApi = container.isUseLegacyDRM &&
        !container.isLaunchRealSteam &&
        !container.isLaunchBionicSteam
    val systemRegFile = File(container.rootDir, ".wine/system.reg")
    if (!systemRegFile.isFile) return

    WineRegistryEditor(systemRegFile).use { editor ->
        editor.setCreateKeyIfNotExist(true)

        // Burn extracts the versioned EA files before its MSI/custom actions
        // register the service. Do not mistake that intermediate state for a
        // completed install or Steam will skip a grey/interrupted installer.
        // system.reg stores the concrete control set. CurrentControlSet is a
        // runtime registry alias and is not guaranteed to exist as a literal
        // key while we edit the prefix offline.
        val serviceKey = listOf(
            "System\\ControlSet001\\Services\\EABackgroundService",
            "System\\CurrentControlSet\\Services\\EABackgroundService",
        ).firstOrNull { key ->
            !editor.getStringValue(key, "ImagePath", null).isNullOrEmpty()
        }
        if (serviceKey == null) {
            for (viewPrefix in listOf("Software", "Software\\Wow6432Node")) {
                editor.setStringValue(
                    "$viewPrefix\\Electronic Arts\\EA Desktop",
                    "InstallSuccessful",
                    "false",
                )
            }
            return
        }
        editor.setStringValue(
            serviceKey,
            "ImagePath",
            "$eaDesktopDir\\EABackgroundService.exe -start",
        )

        for (protocol in listOf("origin", "origin2")) {
            val protocolKey = "Software\\Classes\\$protocol"
            editor.setStringValue(protocolKey, null, "URL:Origin Protocol")
            editor.setStringValue(protocolKey, "URL Protocol", "")
            editor.setStringValue("$protocolKey\\DefaultIcon", null, "\"$launcherPath\",0")
            editor.setStringValue(
                "$protocolKey\\shell\\open\\command",
                null,
                "\"$launcherPath\" \"%1\"",
            )
        }

        // Steam launches The Sims 4 through EA's link2ea:// URL. Some Wine
        // installs are missing this association even though Link2EA.exe is
        // installed, which makes start.exe return immediately without opening
        // EA or the game.
        val link2EaProtocolKey = "Software\\Classes\\link2ea"
        editor.setStringValue(link2EaProtocolKey, null, "URL:EA Link Protocol")
        editor.setStringValue(link2EaProtocolKey, "URL Protocol", "")
        editor.setStringValue("$link2EaProtocolKey\\DefaultIcon", null, "\"$link2EaPath\",0")
        editor.setStringValue(
            "$link2EaProtocolKey\\shell\\open\\command",
            null,
            // Link2EA starts the background service itself, but immediately
            // sends its launch request. On a clean prefix the service also
            // installs its VC runtimes before EADesktop can start; its IPC
            // listener becomes available before that bootstrap is finished.
            // Keep the service and protocol handler in the same Wine session,
            // but give the complete first-run bootstrap time to settle.
            "\"C:\\windows\\system32\\cmd.exe\" /d /s /c " +
                "\"\"C:\\windows\\system32\\sc.exe\" start EABackgroundService >nul 2>&1 & " +
                "\"C:\\windows\\system32\\timeout.exe\" /t 30 /nobreak >nul & " +
                "\"$link2EaPath\" \"%1\"\"",
        )

        // Steam-owned EA entitlements are deliberately redirected back through
        // steam://run/<appid>. With a real/Bionic Steam client, hand that URL to
        // Proton's steam.exe. Legacy DRM replaces the game's steam_api64.dll
        // directly, so avoid the cold-client/Proton Steam bridge and start the
        // existing game executable after EA has authenticated the request.
        val steamProtocolKey = "Software\\Classes\\steam"
        val legacyGameExe = gameExeWindowsPath.takeIf { useLegacySteamApi && it != null }
        val steamProtocolTarget = legacyGameExe ?: steamPath
        val steamProtocolCommand = if (legacyGameExe != null) {
            "\"$legacyGameExe\""
        } else {
            "\"$steamPath\" \"%1\""
        }
        editor.setStringValue(steamProtocolKey, null, "URL:Steam Protocol")
        editor.setStringValue(steamProtocolKey, "URL Protocol", "")
        editor.setStringValue("$steamProtocolKey\\DefaultIcon", null, "\"$steamProtocolTarget\",0")
        editor.setStringValue(
            "$steamProtocolKey\\shell\\open\\command",
            null,
            steamProtocolCommand,
        )

        val desktopValues = mapOf(
            "ClientPath" to desktopPath,
            "ClientVersion" to version,
            "CommonAppPathCreated" to "1",
            "DesktopAppPath" to desktopPath,
            "EaConnectLink2EAAppPath" to link2EaPath,
            "EaSteam2EAAppPath" to "$eaDesktopDir\\EASteamLauncher.exe",
            "ErrorReporterPath" to "$eaDesktopDir\\ErrorReporter.exe",
            "InstallLocation" to "C:\\Program Files\\Electronic Arts\\EA Desktop\\$version",
            "IsUnavailable" to "0",
            "LauncherAppPath" to launcherPath,
            "RazorMode" to "0",
        )

        for (viewPrefix in listOf("Software", "Software\\Wow6432Node")) {
            editor.setStringValue("$viewPrefix\\Origin", "ClientPath", desktopPath)
            editor.setStringValue("$viewPrefix\\Electronic Arts\\EADM", "ClientPath", desktopPath)

            val desktopKey = "$viewPrefix\\Electronic Arts\\EA Desktop"
            for ((name, value) in desktopValues) {
                if (name in setOf("CommonAppPathCreated", "IsUnavailable", "RazorMode")) {
                    editor.setDwordValue(desktopKey, name, value.toInt())
                } else {
                    editor.setStringValue(desktopKey, name, value)
                }
            }
        }
    }
}


/**
 * A staged self-update makes EA Desktop demand a client restart mid-session,
 * which tears down any running game with it. Drop staged payloads, clear the
 * pending flag, and keep the version directory unwritable so an update can't
 * re-stage. (Remove the write protection deliberately when an EA update is
 * actually wanted.)
 */
private fun suppressEaSelfUpdate(container: Container) {
    val (version, _) = findInstalledEaDesktop(container) ?: return
    val versionDir = File(container.rootDir, "$EA_DESKTOP_INSTALL_ROOT/$version")

    versionDir.setWritable(true, false)
    versionDir.listFiles()?.forEach { entry ->
        val staged = entry.name != "EA Desktop" && entry.name != "VC" &&
            (entry.isDirectory || entry.name.endsWith(".zip") || entry.name.endsWith(".zip.sig"))
        if (staged) {
            Timber.tag("GameFixes").i("Removing staged EA update: %s", entry.name)
            entry.deleteRecursively()
        }
    }
    versionDir.setWritable(false, false)

    val machineIni = File(container.rootDir, ".wine/drive_c/ProgramData/EA Desktop/machine.ini")
    if (machineIni.isFile) {
        val lines = machineIni.readLines().map { line ->
            when {
                line.startsWith("machine.updatepending=") -> "machine.updatepending=0"
                line.startsWith("machine.updateinfo=") -> "machine.updateinfo="
                else -> line
            }
        }
        machineIni.writeText(lines.joinToString("\n"))
    }
}

const val EA_LINK2EA_LAUNCH_SCRIPT_WINDOWS_PATH =
    "C:\\\\ProgramData\\\\GameNative\\\\ea-link2ea-launch.cmd"

/**
 * Link2EA races EABackgroundService on cold boots: it starts the service and
 * immediately sends its launch request, so the first EA Desktop of a session
 * connects before the IPC listener exists and shows "disconnected, restart".
 * Start the service and give it a settle window before the handoff. This
 * cannot live in the link2ea registry handler because EA rewrites its protocol
 * keys on every service start. First-run login is handled before this ever
 * runs: the headless install cmd opens the EA Desktop UI after installing so
 * the user can sign in, and closing that window resumes the launch chain.
 *
 * KNOWN REQUIREMENT: on a fresh prefix the game must be launched once WITHOUT
 * Bionic Steam (the coldclient path) before Bionic Steam launches work with
 * the EA launcher. That first boot leaves durable state behind (EA Desktop's
 * compiled UI cache and the coldclient Steam files are the candidates; exact
 * mechanism not yet isolated) without which EA parks the link2ea request as
 * PendingLink2EARequest and silently drops it.
 */
private fun writeLink2EaLaunchScript(container: Container) {
    val (_, eaDesktopDir) = findInstalledEaDesktop(container) ?: return
    val link2EaPath = "$eaDesktopDir\\Link2EA.exe"
    val script = File(container.rootDir, ".wine/drive_c/ProgramData/GameNative/ea-link2ea-launch.cmd")
    script.parentFile?.mkdirs()
    // Straight-line on purpose: wine cmd nondeterministically deadlocks
    // spawning children inside for-loops, so no loops, no polling.
    script.writeText(
        """
        @echo off
        echo Starting EA Background Service...
        "C:\windows\system32\sc.exe" start EABackgroundService >nul 2>&1
        "C:\windows\system32\timeout.exe" /t 5 /nobreak >nul 2>&1
        echo Asking EA to launch the game...
        "$link2EaPath" %1
        """.trimIndent().replace("\n", "\r\n"),
    )
}


private const val EA_INSTALLER_RELATIVE_DIR = "__Installer/Origin/redist/internal"

/**
 * Carve the payload CAB out of EAappInstaller.exe (WiX Burn attached
 * container) and pull the MSI from it with imagefs cabextract. The CAB is
 * found by scanning for MSCF headers whose declared size (u32 LE at +8) is
 * plausible for the ~240MB payload; the extracted MSI is recognized by its
 * OLE compound-file signature.
 */
private fun extractMsiFromEaInstaller(context: Context, internalDir: File, msi: File): Boolean {
    val installer = File(internalDir, "EAappInstaller.exe")
    if (!installer.isFile) return false
    val workDir = File(internalDir, ".gn-msi-extract")
    try {
        val (cabOffset, cabSize) = findPayloadCab(installer) ?: run {
            Timber.tag("GameFixes").w("No payload CAB found in %s", installer.absolutePath)
            return false
        }
        workDir.deleteRecursively()
        workDir.mkdirs()
        val cab = File(workDir, "payload.cab")
        java.io.RandomAccessFile(installer, "r").use { raf ->
            raf.seek(cabOffset)
            cab.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var remaining = cabSize
                while (remaining > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
        }
        val imageFsRoot = com.winlator.xenvironment.ImageFs.find(context).rootDir.absolutePath
        val cmd = mutableListOf<String>()
        if (app.gamenative.BuildConfig.MODERN_ANDROID) cmd.add("/system/bin/linker64")
        cmd.add("$imageFsRoot/usr/bin/cabextract")
        cmd.addAll(listOf("-q", "-d", workDir.absolutePath, cab.absolutePath))
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).apply {
            environment()["LD_LIBRARY_PATH"] = "$imageFsRoot/usr/lib"
        }.start()
        val output = proc.inputStream.bufferedReader().readText()
        if (proc.waitFor() != 0) {
            Timber.tag("GameFixes").w("cabextract failed: %s", output.take(500))
            return false
        }
        val oleSig = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())
        val extracted = workDir.walkTopDown()
            .filter { it.isFile && it != cab && it.length() > 50L * 1024 * 1024 }
            .filter { f -> f.inputStream().use { s -> ByteArray(4).let { s.read(it); it.contentEquals(oleSig) } } }
            .maxByOrNull { it.length() }
            ?: run {
                Timber.tag("GameFixes").w("cabextract produced no MSI-signature payload")
                return false
            }
        if (!extracted.renameTo(msi)) extracted.copyTo(msi, overwrite = true)
        Timber.tag("GameFixes").i(
            "Extracted EA headless MSI (%d bytes) from %s", msi.length(), installer.name,
        )
        return true
    } catch (e: Exception) {
        Timber.tag("GameFixes").e(e, "EA MSI extraction failed")
        return false
    } finally {
        workDir.deleteRecursively()
    }
}

/** Scan for an MSCF header whose declared cbCabinet spans a payload-sized region. */
private fun findPayloadCab(installer: File): Pair<Long, Long>? {
    val fileLen = installer.length()
    val magic = "MSCF".toByteArray(Charsets.US_ASCII)
    java.io.RandomAccessFile(installer, "r").use { raf ->
        val buf = ByteArray(1 shl 20)
        val overlap = 16
        var base = 0L
        var carry = ByteArray(0)
        while (base < fileLen) {
            raf.seek(base)
            val n = raf.read(buf)
            if (n <= 0) break
            val window = carry + buf.copyOf(n)
            var i = 0
            while (true) {
                i = indexOfBytes(window, magic, i)
                if (i < 0) break
                val offset = base - carry.size + i
                if (offset + 12 <= fileLen) {
                    raf.seek(offset + 8)
                    val b = ByteArray(4)
                    raf.readFully(b)
                    val cbCabinet = ((b[3].toLong() and 0xFF) shl 24) or
                        ((b[2].toLong() and 0xFF) shl 16) or
                        ((b[1].toLong() and 0xFF) shl 8) or
                        (b[0].toLong() and 0xFF)
                    if (cbCabinet > 100L * 1024 * 1024 && offset + cbCabinet <= fileLen) {
                        return offset to cbCabinet
                    }
                }
                i++
            }
            carry = window.copyOfRange(maxOf(0, window.size - overlap), window.size)
            base += n
        }
    }
    return null
}

private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, from: Int): Int {
    outer@ for (i in from..haystack.size - needle.size) {
        for (j in needle.indices) {
            if (haystack[i + j] != needle[j]) continue@outer
        }
        return i
    }
    return -1
}

/** An EA App title ships EA's installer bundle inside its game directory. */
fun isEaAppGame(installPath: String): Boolean =
    File(installPath, "$EA_INSTALLER_RELATIVE_DIR/EAappInstaller.exe").isFile

/**
 * The headless-install pieces are EA-App-generic, but the MSI is ~245MB and
 * cannot ship inside the APK. Generate the cmd from code, and source the MSI
 * from any sibling EA game under the same steamapps/common root that already
 * has one, or extract it from the game's own EAappInstaller.exe (a WiX Burn
 * bundle whose attached payload container is a plain CAB holding the MSI).
 */
private fun ensureHeadlessInstallerFiles(context: Context, installPath: String) {
    val internalDir = File(installPath, EA_INSTALLER_RELATIVE_DIR)
    if (!internalDir.isDirectory) return

    val msi = File(internalDir, "EAapp-wine-no-start.msi")
    if (!msi.isFile) {
        val commonRoot = File(installPath).parentFile
        val donor = commonRoot?.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.map { File(it, "$EA_INSTALLER_RELATIVE_DIR/EAapp-wine-no-start.msi") }
            ?.firstOrNull { it.isFile }
        if (donor != null) {
            Timber.tag("GameFixes").i("Copying EA headless MSI from %s", donor.absolutePath)
            donor.copyTo(msi)
        } else if (!extractMsiFromEaInstaller(context, internalDir, msi)) {
            Timber.tag("GameFixes").w(
                "No EAapp-wine-no-start.msi under %s and extraction failed; EA headless install unavailable",
                commonRoot?.absolutePath,
            )
            return
        }
    }

    val cmd = File(internalDir, "EAapp-wine-install.cmd")
    if (!cmd.isFile) {
        cmd.writeText(
            """
            @echo off
            echo Preparing the EA app installer...
            set EA_BUNDLE_KEY={0843d159-4e03-4026-8fa0-32432514dba6}
            set "EA_BUNDLE_CACHE=C:\ProgramData\Package Cache\%EA_BUNDLE_KEY%"
            if not exist "%EA_BUNDLE_CACHE%" mkdir "%EA_BUNDLE_CACHE%"
            copy /Y "%~dp0EAappInstaller.exe" "%EA_BUNDLE_CACHE%\EAappOfflineInstaller.exe" >nul
            reg add "HKLM\SOFTWARE\Wow6432Node\Microsoft\Windows\CurrentVersion\Uninstall\%EA_BUNDLE_KEY%" /f >nul
            reg add "HKLM\SOFTWARE\Electronic Arts\EA Desktop" /v InstallSuccessful /t REG_SZ /d installing /f >nul
            echo Removing any previous EA app install...
            msiexec /x {C2622085-ABD2-49E5-8AB9-D3D6A642C091} /qn /norestart
            rem Pre-register the service: on a fresh prefix wine msi's
            rem StartServices cannot see the service its own ServiceInstall
            rem just registered (error 2) and rolls the whole install back.
            sc create EABackgroundService binPath= "\"C:\Program Files\Electronic Arts\EA Desktop\13.768.7.6285\EA Desktop\EABackgroundService.exe\" -start" >nul 2>&1
            echo Installing the EA app. This takes a few minutes - do not close this window.
            msiexec /i "%~dp0EAapp-wine-no-start.msi" /qn /norestart /L*V "C:\windows\temp\EAapp-wine-install.log" ARPSYSTEMCOMPONENT=1 MSIFASTINSTALL=7 INSTALL_ROOT="C:\Program Files\Electronic Arts\EA Desktop" CLIENT_VERSION="13.768.7.6285" JUNO_CREATE_DESKTOP_SHORTCUT=1 INSTALLER_ERROR_REPORTER_SHORTCUT_TITLE="EA Error Reporter" INSTALLER_UPDATER_SHORTCUT_TITLE="EA app Updater" INSTALLER_RECOVERY_HELPER_SHORTCUT_TITLE="App Recovery" INSTALLER_ERROR_UNKNOWN="EA app encountered an error during the installation. Try again a bit later." BUNDLE_PROVIDERKEY="%EA_BUNDLE_KEY%" PRODUCT_DISPLAY_NAME="EA app" EAX_LAUNCH_CLIENT=0 INTREPID_ENABLE=0 EAX_ALLOW_WINDOWS_7=1 EAX_DISABLE_SYMLINKS=0 EAX_DOWNLOAD_IN_PLACE_DIR="C:\Program Files\EA Games" EAX_BUNDLE_EXECUTABLE_NAME="EAappOfflineInstaller.exe" EAX_RAZOR_MODE_ENABLE=0
            set EA_INSTALL_RC=%ERRORLEVEL%
            copy /Y "C:\windows\temp\EAapp-wine-install.log" "%~dp0EAapp-wine-install.log" >nul
            if not "%EA_INSTALL_RC%"=="0" goto install_failed
            reg add "HKLM\SOFTWARE\Electronic Arts\EA Desktop" /v InstallSuccessful /t REG_SZ /d true /f >nul
            echo EA app installed successfully.
            echo.
            echo Opening the EA app so you can sign in.
            echo After signing in, CLOSE the EA app window to continue to the game.
            "C:\Program Files\Electronic Arts\EA Desktop\13.768.7.6285\EA Desktop\EADesktop.exe"
            echo Continuing to the game...
            exit /b 0
            :install_failed
            echo EA app install failed with code %EA_INSTALL_RC%. The game may not start.
            reg add "HKLM\SOFTWARE\Electronic Arts\EA Desktop" /v InstallSuccessful /t REG_SZ /d failed-%EA_INSTALL_RC% /f >nul
            exit /b %EA_INSTALL_RC%
            """.trimIndent().replace("\n", "\r\n"),
        )
    }
}

/**
 * EA App family fix: applies to any Steam game that ships the EA installer.
 *
 * Keep the game on DXVK, route EA's Qt hosts through builtin WineD3D without
 * disabling OpenGL, and force only the CEF subprocess to software rendering.
 */
val EaAppGameFix: GameFix = object : GameFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean = try {
        ensureHeadlessInstallerFiles(context, installPath)
        configureEaInstallScript(installPath)
        writeLink2EaLaunchScript(container)
        suppressEaSelfUpdate(container)
        val userRegFile = File(container.rootDir, ".wine/user.reg")
        if (!userRegFile.isFile) {
            userRegFile.parentFile?.mkdirs()
            userRegFile.writeText("WINE REGISTRY Version 2\n\n")
        }
        WineRegistryEditor(userRegFile).use { editor ->
            editor.setCreateKeyIfNotExist(true)
            for (exe in EA_QT_HOST_EXES) {
                val dllOverridesKey = "Software\\Wine\\AppDefaults\\$exe\\DllOverrides"
                val direct3dKey = "Software\\Wine\\AppDefaults\\$exe\\Direct3D"
                for (dll in EA_SOFTWARE_RENDERER_DLLS) {
                    if (editor.getStringValue(dllOverridesKey, dll, null) != "builtin") {
                        editor.setStringValue(dllOverridesKey, dll, "builtin")
                    }
                }
                editor.removeValue(direct3dKey, "renderer")
            }
            for (exe in EA_SOFTWARE_RENDERER_EXES) {
                val dllOverridesKey = "Software\\Wine\\AppDefaults\\$exe\\DllOverrides"
                val direct3dKey = "Software\\Wine\\AppDefaults\\$exe\\Direct3D"
                for (dll in EA_SOFTWARE_RENDERER_DLLS) {
                    if (editor.getStringValue(dllOverridesKey, dll, null) != "builtin") {
                        editor.setStringValue(dllOverridesKey, dll, "builtin")
                    }
                }
                if (editor.getStringValue(direct3dKey, "renderer", null) != "no3d") {
                    editor.setStringValue(direct3dKey, "renderer", "no3d")
                }
            }
        }
        val gameExeWindowsPath = container.executablePath
            .takeIf { it.isNotBlank() }
            ?.let { exe ->
                "C:\\Program Files (x86)\\Steam\\steamapps\\common\\" +
                    "${File(installPath).name}\\${exe.replace('/', '\\')}"
            }
        applyEaCompatibilityRegistry(container, gameExeWindowsPath)
        true
    } catch (e: Exception) {
        Timber.tag("GameFixes").e(e, "Failed to apply EA App software renderer overrides")
        false
    }
}
