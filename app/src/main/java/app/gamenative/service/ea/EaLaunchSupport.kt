package app.gamenative.service.ea

import android.content.Context
import app.gamenative.data.LaunchInfo
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.core.WineRegistryEditor
import java.io.File
import timber.log.Timber

/**
 * Real-Steam launches of EA titles: Steam's launch config is a link2ea:// URL that the EA app
 * would normally handle. We register our Wine-side stub for that protocol, and run the LSX
 * server the game talks to once the stub has started it.
 */
object EaLaunchSupport {
    fun isEaLaunch(launchInfo: LaunchInfo?): Boolean {
        val exe = launchInfo?.executable?.lowercase() ?: return false
        return exe.startsWith("link2ea://") || exe.startsWith("steam2ea://")
    }

    /**
     * EA-published Steam titles launch through link2ea:// (a URL entry the app's .exe-only
     * launch-info filter drops), and ship EA's __Installer metadata next to the game.
     */
    fun isEaTitle(steamAppId: Int, gameDir: File?): Boolean {
        val launch = SteamService.getAppInfoOf(steamAppId)?.config?.launch.orEmpty()
        if (launch.any { isEaLaunch(it) }) return true
        return gameDir != null && File(gameDir, "__Installer/installerdata.xml").exists()
    }

    /** Registers the protocol handlers and launcher presence keys in the prefix's HKLM hive. */
    fun setupPrefix(container: Container) {
        val systemReg = File(container.rootDir, ".wine/system.reg")
        val command = "\"${EaConstants.STUB_EXE}\" \"%1\""
        try {
            WineRegistryEditor(systemReg).use { editor ->
                editor.setCreateKeyIfNotExist(true)
                for (proto in listOf("link2ea", "steam2ea")) {
                    editor.setStringValue("Software\\Classes\\$proto", null, "URL:$proto Protocol")
                    editor.setStringValue("Software\\Classes\\$proto", "URL Protocol", "")
                    editor.setStringValue("Software\\Classes\\$proto\\shell\\open\\command", null, command)
                }
                // Steam's client resolves link2ea through the EA Desktop registry and runs the
                // EaConnectLink2EAAppPath executable directly; the game and client also start
                // ClientPath/DesktopAppPath when they think EA Desktop is not running. Point all of
                // them at the stub so a leftover EA app install in the prefix never starts.
                for (hive in listOf("Software\\Electronic Arts\\EA Desktop", "Software\\Wow6432Node\\Electronic Arts\\EA Desktop")) {
                    editor.setStringValue(hive, "EaConnectLink2EAAppPath", EaConstants.STUB_EXE)
                    editor.setStringValue(hive, "EaConnectMsAppPath", EaConstants.STUB_EXE)
                    editor.setStringValue(hive, "ClientPath", EaConstants.STUB_EXE)
                    editor.setStringValue(hive, "DesktopAppPath", EaConstants.STUB_EXE)
                    editor.setStringValue(hive, "InstallSuccessful", "true")
                }
                for (hive in listOf("Software\\Electronic Arts\\EADM", "Software\\Wow6432Node\\Electronic Arts\\EADM", "Software\\Origin", "Software\\Wow6432Node\\Origin")) {
                    editor.setStringValue(hive, "ClientPath", EaConstants.STUB_EXE)
                }
            }
            Timber.i("EA: registered link2ea/steam2ea handlers and launcher keys in ${systemReg.name}")
        } catch (e: Exception) {
            Timber.e(e, "EA: failed to write registry keys")
        }
    }

    fun start(
        context: Context,
        container: Container,
        steamAppId: Int,
        gameDir: File,
        gameDirWindows: String,
        exeRelative: String,
        arguments: String,
    ) {
        if (!EaAuthManager.isLoggedIn(context)) {
            Timber.e("EA: no EA account signed in; the game will not be able to authenticate")
        }
        setupPrefix(container)
        val session = EaLaunchSession(
            context = context.applicationContext,
            prefixDriveC = File(container.rootDir, ".wine/drive_c"),
            gameDir = gameDir,
            gameDirWindows = gameDirWindows,
            exeRelativeWindows = exeRelative.replace('/', '\\').trimStart('\\'),
            arguments = arguments,
            steamAppId = steamAppId,
        )
        Timber.i("EA: LSX session for app $steamAppId exe=${session.exeRelativeWindows} contentId=${session.contentId.ifEmpty { "(from game)" }}")
        EaLsxServer.start(session)
    }

    fun stop() {
        if (EaLsxServer.isRunning()) EaLsxServer.stop()
    }
}
