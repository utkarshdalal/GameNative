package app.gamenative.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.os.IBinder
import androidx.room.withTransaction
import app.gamenative.BuildConfig
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.ThreadSafeManifestProvider
import app.gamenative.data.DepotInfo
import app.gamenative.data.DownloadInfo
import app.gamenative.data.Emoticon
import app.gamenative.data.GameProcessInfo
import app.gamenative.data.LaunchInfo
import app.gamenative.data.OwnedGames
import app.gamenative.data.PostSyncInfo
import app.gamenative.data.SteamApp
import app.gamenative.data.SteamFriend
import app.gamenative.data.SteamLicense
import app.gamenative.data.UserFileInfo
import app.gamenative.db.PluviaDatabase
import app.gamenative.db.dao.ChangeNumbersDao
import app.gamenative.db.dao.EmoticonDao
import app.gamenative.db.dao.FileChangeListsDao
import app.gamenative.db.dao.FriendMessagesDao
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.db.dao.SteamFriendDao
import app.gamenative.db.dao.SteamLicenseDao
import app.gamenative.enums.LoginResult
import app.gamenative.enums.OS
import app.gamenative.enums.OSArch
import app.gamenative.enums.SaveLocation
import app.gamenative.enums.SyncResult
import app.gamenative.events.AndroidEvent
import app.gamenative.events.SteamEvent
import app.gamenative.service.callback.EmoticonListCallback
import app.gamenative.service.handler.PluviaHandler
import app.gamenative.utils.SteamUtils
import app.gamenative.utils.generateSteamApp
import com.google.android.play.core.ktx.bytesDownloaded
import com.google.android.play.core.ktx.requestCancelInstall
import com.google.android.play.core.ktx.requestInstall
import com.google.android.play.core.ktx.requestSessionState
import com.google.android.play.core.ktx.status
import com.google.android.play.core.ktx.totalBytesToDownload
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import com.winlator.xenvironment.ImageFs
import dagger.hilt.android.AndroidEntryPoint
import `in`.dragonbra.javasteam.enums.EDepotFileFlag
import `in`.dragonbra.javasteam.enums.EFriendRelationship
import `in`.dragonbra.javasteam.enums.ELicenseFlags
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.enums.EPersonaState
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.networking.steam3.ProtocolTypes
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientObjects.ECloudPendingRemoteOperation
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesFamilygroupsSteamclient
import `in`.dragonbra.javasteam.rpc.service.FamilyGroups
import `in`.dragonbra.javasteam.steam.authentication.AuthPollResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.AuthenticationException
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import `in`.dragonbra.javasteam.steam.authentication.IChallengeUrlChanged
import `in`.dragonbra.javasteam.steam.authentication.QrAuthSession
import `in`.dragonbra.javasteam.steam.contentdownloader.ContentDownloader
import `in`.dragonbra.javasteam.steam.discovery.FileServerListProvider
import `in`.dragonbra.javasteam.steam.discovery.ServerQuality
import `in`.dragonbra.javasteam.steam.handlers.steamapps.GamePlayedInfo
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.SteamFriends
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.AliasHistoryCallback
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.FriendsListCallback
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.NicknameListCallback
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.PersonaStateCallback
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.ProfileInfoCallback
import `in`.dragonbra.javasteam.steam.handlers.steamgameserver.SteamGameServer
import `in`.dragonbra.javasteam.steam.handlers.steammasterserver.SteamMasterServer
import `in`.dragonbra.javasteam.steam.handlers.steamscreenshots.SteamScreenshots
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.handlers.steamuser.ChatMode
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.SteamUserStats
import `in`.dragonbra.javasteam.steam.handlers.steamworkshop.SteamWorkshop
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration
import `in`.dragonbra.javasteam.types.FileData
import `in`.dragonbra.javasteam.types.SteamID
import `in`.dragonbra.javasteam.util.NetHelpers
import `in`.dragonbra.javasteam.util.log.LogListener
import `in`.dragonbra.javasteam.util.log.LogManager
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Collections
import java.util.EnumSet
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.io.path.pathString
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import timber.log.Timber
import java.lang.NullPointerException
import java.util.concurrent.TimeUnit
import android.os.Environment
import android.os.SystemClock

@AndroidEntryPoint
class SteamService : Service(), IChallengeUrlChanged {

    @Inject
    lateinit var db: PluviaDatabase

    @Inject
    lateinit var licenseDao: SteamLicenseDao

    @Inject
    lateinit var appDao: SteamAppDao

    @Inject
    lateinit var friendDao: SteamFriendDao

    @Inject
    lateinit var messagesDao: FriendMessagesDao

    @Inject
    lateinit var emoticonDao: EmoticonDao

    @Inject
    lateinit var changeNumbersDao: ChangeNumbersDao

    @Inject
    lateinit var fileChangeListsDao: FileChangeListsDao

    private lateinit var notificationHelper: NotificationHelper

    internal var callbackManager: CallbackManager? = null
    internal var steamClient: SteamClient? = null
    internal val callbackSubscriptions: ArrayList<Closeable> = ArrayList()

    private var _unifiedFriends: SteamUnifiedFriends? = null
    private var _steamUser: SteamUser? = null
    private var _steamApps: SteamApps? = null
    private var _steamFriends: SteamFriends? = null
    private var _steamCloud: SteamCloud? = null
    private var _steamFamilyGroups: FamilyGroups? = null

    private var _loginResult: LoginResult = LoginResult.Failed

    private var retryAttempt = 0

    private val appPicsChannel = Channel<List<PICSRequest>>(
        capacity = 1_000,
        onBufferOverflow = BufferOverflow.SUSPEND,
        onUndeliveredElement = { droppedApps ->
            Timber.w("App PICS Channel dropped: ${droppedApps.size} apps")
        },
    )

    private val packagePicsChannel = Channel<List<PICSRequest>>(
        capacity = 1_000,
        onBufferOverflow = BufferOverflow.SUSPEND,
        onUndeliveredElement = { droppedPackages ->
            Timber.w("Package PICS Channel dropped: ${droppedPackages.size} packages")
        },
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val onEndProcess: (AndroidEvent.EndProcess) -> Unit = {
        Companion.stop()
    }

    // The current shared family group the logged in user is joined to.
    private var familyGroupMembers: ArrayList<Int> = arrayListOf()

    private val appTokens: ConcurrentHashMap<Int, Long> = ConcurrentHashMap()

    // Connectivity management for Wi-Fi-only downloads
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback
    private var isWifiConnected: Boolean = true

    companion object {
        const val MAX_PICS_BUFFER = 256

        const val MAX_RETRY_ATTEMPTS = 20

        const val INVALID_APP_ID: Int = Int.MAX_VALUE
        const val INVALID_PKG_ID: Int = Int.MAX_VALUE

        /**
         * Default timeout to use when making requests
         */
        var requestTimeout = 10.seconds

        /**
         * Default timeout to use when reading the response body
         */
        var responseTimeout = 60.seconds

        private val PROTOCOL_TYPES = EnumSet.of(ProtocolTypes.WEB_SOCKET)

        private var instance: SteamService? = null

        const val DOWNLOAD_COMPLETE_MARKER = ".download_complete"

        private val downloadJobs = ConcurrentHashMap<Int, DownloadInfo>()

        /** Returns true if there is an incomplete download on disk (no complete marker). */
        fun hasPartialDownload(appId: Int): Boolean {
            val dir = File(getAppDirPath(appId))
            val marker = File(dir, DOWNLOAD_COMPLETE_MARKER)
            return dir.exists() && !marker.exists()
        }

        private var syncInProgress: Boolean = false

        var isStopping: Boolean = false
            private set
        var isConnected: Boolean = false
            private set
        var isRunning: Boolean = false
            private set
        var isLoggingOut: Boolean = false
            private set
        val isLoggedIn: Boolean
            get() = instance?.steamClient?.steamID?.isValid == true
        var isWaitingForQRAuth: Boolean = false
            private set

        private val serverListPath: String
            get() = Paths.get(instance!!.cacheDir.path, "server_list.bin").pathString

        private val depotManifestsPath: String
            get() = Paths.get(instance!!.dataDir.path, "Steam", "depot_manifests.zip").pathString

        val internalAppInstallPath: String
            get() {
                if (instance != null) {
                    return Paths.get(instance!!.dataDir.path, "Steam", "steamapps", "common").pathString
                }
                return ""
            }
        val externalAppInstallPath: String
            get() {
                return Paths.get(PrefManager.externalStoragePath, "Steam", "steamapps", "common").pathString
            }

        private val internalAppStagingPath: String
            get() {
                return Paths.get(instance!!.dataDir.path, "Steam", "steamapps", "staging").pathString
            }
        private val externalAppStagingPath: String
            get() {
                return Paths.get(PrefManager.externalStoragePath, "Steam", "steamapps", "staging").pathString
            }

        val defaultAppInstallPath: String
            get() {
                return if (PrefManager.useExternalStorage && File(PrefManager.externalStoragePath).exists()) {
                    // We still have an SD card file structure as expected
                    Timber.i("Using external storage")
                    Timber.i("install path for external storage is " + externalAppInstallPath)
                    externalAppInstallPath
                } else {
                    Timber.i("Using internal storage")
                    internalAppInstallPath
                }
            }

        val defaultAppStagingPath: String
            get() {
                return if (PrefManager.useExternalStorage) {
                    externalAppStagingPath
                } else {
                    internalAppStagingPath
                }
            }

        val userSteamId: SteamID?
            get() = instance?.steamClient?.steamID

        val familyMembers: List<Int>
            get() = instance!!.familyGroupMembers

        val isLoginInProgress: Boolean
            get() = instance!!._loginResult == LoginResult.InProgress

        private const val MAX_PARALLEL_DEPOTS   = 2     // instead of all 38
        private const val CHUNKS_PER_DEPOT      = 16

        // simple depot-level semaphore
        private val depotGate = Semaphore(MAX_PARALLEL_DEPOTS)
        suspend fun setPersonaState(state: EPersonaState) = withContext(Dispatchers.IO) {
            PrefManager.personaState = state
            instance?._steamFriends?.setPersonaState(state)
        }

        suspend fun requestUserPersona() = withContext(Dispatchers.IO) {
            // in order to get user avatar url and other info
            userSteamId?.let { instance?._steamFriends?.requestFriendInfo(it) }
        }

        suspend fun getPersonaStateOf(steamId: SteamID): SteamFriend? = withContext(Dispatchers.IO) {
            instance!!.db.steamFriendDao().findFriend(steamId.convertToUInt64())
        }

        fun getPkgInfoOf(appId: Int): SteamLicense? {
            return runBlocking(Dispatchers.IO) {
                instance?.licenseDao?.findLicense(
                    instance?.appDao?.findApp(appId)?.packageId ?: INVALID_PKG_ID,
                )
            }
        }

        fun getAppInfoOf(appId: Int): SteamApp? {
            return runBlocking(Dispatchers.IO) { instance?.appDao?.findApp(appId) }
        }

        fun getAppDownloadInfo(appId: Int): DownloadInfo? {
            return downloadJobs[appId]
        }

        fun isAppInstalled(appId: Int): Boolean {
            val dir = File(getAppDirPath(appId))
            val markerFile = File(dir, DOWNLOAD_COMPLETE_MARKER)
            val appDownloadInfo = getAppDownloadInfo(appId)
            val isNotDownloading = appDownloadInfo == null || appDownloadInfo.getProgress() >= 1f
            val appDirPath = Paths.get(getAppDirPath(appId))
            val pathExists = Files.exists(appDirPath)

            // logD("isDownloading: $isNotDownloading && pathExists: $pathExists && appDirPath: $appDirPath")

            return isNotDownloading && pathExists && markerFile.exists()
        }

        fun getAppDlc(appId: Int): Map<Int, DepotInfo> {
            return getAppInfoOf(appId)?.let {
                it.depots.filter { it.value.dlcAppId != INVALID_APP_ID }
            }.orEmpty()
        }

        fun getOwnedAppDlc(appId: Int): Map<Int, DepotInfo> = getAppDlc(appId).filter {
            getPkgInfoOf(it.value.dlcAppId)?.let { pkg ->
                instance?.steamClient?.let { steamClient ->
                    pkg.ownerAccountId.contains(steamClient.steamID?.accountID?.toInt())
                }
            } == true
        }

        fun getDownloadableDepots(appId: Int): Map<Int, DepotInfo> {
            val appInfo   = getAppInfoOf(appId) ?: return emptyMap()
            val ownedDlc  = getOwnedAppDlc(appId)

            return appInfo.depots
                .asSequence()
                .filter { (_, depot) ->
                    if (depot.manifests.isEmpty() && depot.encryptedManifests.isNotEmpty())
                        return@filter false
                    // 1. Has something to download
                    if (depot.manifests.isEmpty() && !depot.sharedInstall)
                        return@filter false
                    // 2. Supported OS
                    if (!(depot.osList.contains(OS.windows) ||
                                (!depot.osList.contains(OS.linux) && !depot.osList.contains(OS.macos))))
                        return@filter false
                    // 3. 64-bit or indeterminate
                    if (!(depot.osArch == OSArch.Arch64 || depot.osArch == OSArch.Unknown || depot.osArch == OSArch.Arch32))
                        return@filter false
                    // 4. DLC you actually own
                    depot.dlcAppId == INVALID_APP_ID || ownedDlc.containsKey(depot.dlcAppId)
                }
                .associate { it.toPair() }
        }

        fun getAppDirName(app: SteamApp?): String {
            // The folder name, if it got made
            var appName = app?.config?.installDir.orEmpty()
            if (appName.isEmpty()) {
                appName = app?.name.orEmpty()
            }
            return appName
        }

        fun getAppDirPath(appId: Int): String {

            val appName = getAppDirName(getAppInfoOf(appId))

            // Internal first (legacy installs), external second
            val internalPath = Paths.get(internalAppInstallPath, appName)
            if (Files.exists(internalPath)) return internalPath.pathString

            val externalPath = Paths.get(externalAppInstallPath, appName)
            if (Files.exists(externalPath)) return externalPath.pathString

            // Nothing on disk yet – default to whatever location you want new installs to use
            if (PrefManager.useExternalStorage) {
                return externalPath.pathString
            }
            return internalPath.pathString
        }

        private fun isExecutable(flags: Any): Boolean = when (flags) {
            // SteamKit-JVM (most forks) – flags is EnumSet<EDepotFileFlag>
            is EnumSet<*> -> {
                flags.contains(EDepotFileFlag.Executable) ||
                        flags.contains(EDepotFileFlag.CustomExecutable)
            }

            // SteamKit-C# protobuf port – flags is UInt / Int / Long
            is Int  -> (flags and 0x20) != 0 || (flags and 0x80) != 0
            is Long -> ((flags and 0x20L) != 0L) || ((flags and 0x80L) != 0L)

            else    -> false
        }

        /* -------------------------------------------------------------------------- */
        /* 1. Extra patterns & word lists                                             */
        /* -------------------------------------------------------------------------- */

        // Unreal Engine "Shipping" binaries (e.g. Stray-Win64-Shipping.exe)
        private val UE_SHIPPING = Regex(""".*-win(32|64)(-shipping)?\.exe$""",
            RegexOption.IGNORE_CASE)

        // UE folder hint …/Binaries/Win32|64/…
        private val UE_BINARIES = Regex(""".*/binaries/win(32|64)/.*\.exe$""",
            RegexOption.IGNORE_CASE)

        // Tools / crash-dumpers to push down
        private val NEGATIVE_KEYWORDS = listOf(
            "crash", "handler", "viewer", "compiler", "tool",
            "setup", "unins", "eac", "launcher", "steam"
        )
        /* add near-name helper */
        private fun fuzzyMatch(a: String, b: String): Boolean {
            /* strip digits & punctuation, compare first 5 letters */
            val cleanA = a.replace(Regex("[^a-z]"), "")
            val cleanB = b.replace(Regex("[^a-z]"), "")
            return cleanA.take(5) == cleanB.take(5)
        }

        /* add generic short-name detector: one letter + digits, ≤4 chars  */
        private val GENERIC_NAME = Regex("^[a-z]\\d{1,3}\\.exe$", RegexOption.IGNORE_CASE)

        /* -------------------------------------------------------------------------- */
        /* 2. Heuristic score (same signature!)                                       */
        /* -------------------------------------------------------------------------- */

        private fun scoreExe(
            file: FileData,
            gameName: String,
            hasExeFlag: Boolean
        ): Int {
            var s = 0
            val path = file.fileName.lowercase()

            // 1️⃣ UE shipping or binaries folder bonus
            if (UE_SHIPPING.matches(path))      s += 300
            if (UE_BINARIES.containsMatchIn(path)) s += 250

            // 2️⃣ root-folder exe bonus
            if (!path.contains('/'))            s += 200

            // 3️⃣ filename contains the game / installDir
            if (path.contains(gameName) || fuzzyMatch(path, gameName))  s += 100

            // 4️⃣ obvious tool / crash-dumper penalty
            if (NEGATIVE_KEYWORDS.any { it in path }) s -= 150
            if (GENERIC_NAME.matches(file.fileName))                    s -= 200   // ← new

            // 5️⃣ Executable | CustomExecutable flag
            if (hasExeFlag)                     s += 50

            return s
        }

        /** select the primary binary */
        fun choosePrimaryExe(
            files: List<FileData>?,
            gameName: String
        ): FileData? = files?.maxWithOrNull { a, b ->
            val sa = scoreExe(a, gameName, isExecutable(a.flags))   // <- fixed
            val sb = scoreExe(b, gameName, isExecutable(b.flags))

            when {
                sa != sb -> sa - sb                                 // higher score wins
                else     -> (a.totalSize - b.totalSize).toInt()     // tie-break on size
            }
        }

        /**
         * Picks the real shipped EXE for a Steam app.
         *
         * ❶ try the dev-supplied launch entry (skip obvious stubs)
         * ❷ else score all manifest-flagged EXEs and keep the best
         * ❸ else fall back to the largest flagged EXE in the biggest depot
         * If everything fails, return the game's install directory.
         */
        fun getInstalledExe(appId: Int): String {
            val appInfo = getAppInfoOf(appId) ?: return ""

            val installDir = appInfo.config.installDir.ifEmpty { appInfo.name }

            val depots = appInfo.depots.values.filter { d ->
                !d.sharedInstall && (d.osList.isEmpty() ||
                        d.osList.any { it.name.equals("windows", true) || it.name.equals("none", true) })
            }
            Timber.i("Depots considered: $depots")

            /* launch targets (lower-case) */
            val launchTargets = appInfo.config.launch
                .mapNotNull { it.executable.lowercase() }.toSet() ?: emptySet()

            Timber.i("Launch targets from appinfo: $launchTargets")

            /* stub detector (same short rules) */
            val generic = Regex("^[a-z]\\d{1,3}\\.exe$", RegexOption.IGNORE_CASE)
            val bad     = listOf("launcher","steam","crash","handler","setup","unins","eac")
            fun FileData.isStub(): Boolean {
                val n = fileName.lowercase()
                val stub = generic.matches(n) || bad.any { it in n } || totalSize < 1_000_000
                if (stub) Timber.d("Stub filtered: $fileName  size=$totalSize")
                return stub
            }

            /* ---------------------------------------------------------- */
            val flagged = mutableListOf<Pair<FileData, Long>>()   // (file, depotSize)
            var largestDepotSize = 0L

            val provider = ThreadSafeManifestProvider(File(depotManifestsPath).toPath())

            for (depot in depots) {
                val mi = depot.manifests["public"] ?: continue
                if (mi.size > largestDepotSize) largestDepotSize = mi.size

                val man = provider.fetchManifest(depot.depotId, mi.gid) ?: continue
                Timber.d("Fetched manifest for depot ${depot.depotId}  size=${mi.size}")

                /* 1️⃣ exact launch entry that isn't a stub */
                man.files.firstOrNull { f ->
                    f.fileName.lowercase() in launchTargets && !f.isStub()
                }?.let {
                    Timber.i("Picked via launch entry: ${it.fileName}")
                    return it.fileName.replace('\\','/').toString()
                }

                /* collect for later */
                man.files.filter { isExecutable(it.flags) || it.fileName.endsWith(".exe", true) }
                    .forEach { flagged += it to mi.size }
            }

            Timber.i("Flagged executable candidates: ${flagged.map { it.first.fileName }}")

            /* 2️⃣ scorer (unchanged) */
            choosePrimaryExe(flagged.map { it.first }, installDir.lowercase())?.let {
                Timber.i("Picked via scorer: ${it.fileName}")
                return it.fileName.replace('\\','/').toString()
            }

            /* 3️⃣ fallback: biggest exe from the biggest depot */
            flagged
                .filter { it.second == largestDepotSize }
                .maxByOrNull { it.first.totalSize }
                ?.let {
                    Timber.i("Picked via largest-depot fallback: ${it.first.fileName}")
                    return it.first.fileName.replace('\\','/').toString()
                }

            /* 4️⃣ last resort */
            Timber.w("No executable found; falling back to install dir")
            return (getAppInfoOf(appId)?.let { appInfo ->
                getWindowsLaunchInfos(appId).firstOrNull()
            })?.executable ?: ""
        }

        fun deleteApp(appId: Int): Boolean {
            // Remove any download-complete marker
            val marker = File(getAppDirPath(appId), DOWNLOAD_COMPLETE_MARKER)
            if (marker.exists()) marker.delete()
            with(instance!!) {
                scope.launch {
                    db.withTransaction {
                        changeNumbersDao.deleteByAppId(appId)
                        fileChangeListsDao.deleteByAppId(appId)
                    }
                }
            }

            val appDirPath = getAppDirPath(appId)

            return File(appDirPath).deleteRecursively()
        }

        fun downloadApp(appId: Int): DownloadInfo? {
            // Enforce Wi-Fi-only downloads
            if (PrefManager.downloadOnWifiOnly && instance?.isWifiConnected == false) {
                instance?.notificationHelper?.notify("Not connected to Wi-Fi")
                return null
            }
            return getAppInfoOf(appId)?.let { appInfo ->
                Timber.i("App contains ${appInfo.depots.size} depot(s): ${appInfo.depots.keys}")
                downloadApp(appId, getDownloadableDepots(appId).keys.toList(), "public")
            }
        }

        fun isImageFsInstalled(context: Context): Boolean {
            return ImageFs.find(context).rootDir.exists()
        }

        fun isImageFsInstallable(context: Context): Boolean {
            val splitManager = SplitInstallManagerFactory.create(context)
            return splitManager.installedModules.contains("ubuntufs") // || FileUtils.assetExists(context.assets, "imagefs_gamenative.txz")
        }

        fun downloadImageFs(
            onDownloadProgress: (Float) -> Unit,
            parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
        ) = parentScope.async {
            if (!isImageFsInstalled(instance!!) && !isImageFsInstallable(instance!!)) {
                Timber.i("imagefs_gamenative.txz will be downloaded")
                val splitManager = SplitInstallManagerFactory.create(instance!!)
                // if (!splitManager.installedModules.contains("ubuntufs")) {
                val moduleInstallSessionId = splitManager.requestInstall(listOf("ubuntufs"))
                var isInstalling = true
                // try {
                do {
                    val sessionState = splitManager.requestSessionState(moduleInstallSessionId)
                    // logD("imagefs_gamenative.txz session state status: ${sessionState.status}")
                    when (sessionState.status) {
                        SplitInstallSessionStatus.INSTALLED -> isInstalling = false
                        SplitInstallSessionStatus.PENDING,
                        SplitInstallSessionStatus.INSTALLING,
                        SplitInstallSessionStatus.DOWNLOADED,
                        SplitInstallSessionStatus.DOWNLOADING,
                        -> {
                            if (!isActive) {
                                Timber.i("ubuntufs module download cancelling due to scope becoming inactive")
                                splitManager.requestCancelInstall(moduleInstallSessionId)
                                break
                            }
                            val downloadPercent =
                                sessionState.bytesDownloaded.toFloat() / sessionState.totalBytesToDownload
                            // logD("imagefs_gamenative.txz download percent: $downloadPercent")
                            // downloadInfo.setProgress(downloadPercent, 0)
                            onDownloadProgress(downloadPercent)
                            delay(100)
                        }

                        else -> {
                            cancel("Failed to install ubuntufs module: ${sessionState.status}")
                        }
                    }
                } while (isInstalling)
                // } catch (e: Exception) {
                //     if (moduleInstallSessionId != -1) {
                //         val splitManager = SplitInstallManagerFactory.create(instance!!)
                //         val sessionState = splitManager.requestSessionState(moduleInstallSessionId)
                //         if (sessionState.status == SplitInstallSessionStatus.DOWNLOADING ||
                //             sessionState.status == SplitInstallSessionStatus.DOWNLOADED ||
                //             sessionState.status == SplitInstallSessionStatus.INSTALLING
                //         ) {
                //             splitManager.requestCancelInstall(moduleInstallSessionId)
                //         }
                //     }
                // }
                val installedProperly = splitManager.installedModules.contains("ubuntufs")
                Timber.i("imagefs_gamenative.txz module installed properly: $installedProperly")
                // }
            } else {
                Timber.i("ubuntufs module already installed, skipping download")
            }
        }

        fun downloadApp(
            appId: Int,
            depotIds: List<Int>,
            branch: String,
        ): DownloadInfo? {
            Timber.d("Attempting to download " + appId + " with depotIds " + depotIds)
            // Enforce Wi-Fi-only downloads
            if (PrefManager.downloadOnWifiOnly && instance?.isWifiConnected == false) {
                instance?.notificationHelper?.notify("Not connected to Wi-Fi")
                return null
            }
            if (downloadJobs.contains(appId)) return getAppDownloadInfo(appId)
            Timber.d("depotIds is empty? " + depotIds.isEmpty())
            if (depotIds.isEmpty()) return null

            val steamApps = instance!!.steamClient!!.getHandler(SteamApps::class.java)!!
            val entitledDepotIds = runBlocking {
                depotIds.map { depotId ->
                    async(Dispatchers.IO) {
                        val result = try {
                            withTimeout(1_000) {          // 5 s is enough for a normal reply
                                steamApps.getDepotDecryptionKey(depotId, appId)
                                    .await()
                                    .result
                            }
                        } catch (e: Exception) {
                            // No reply at all → assume key not required (HL-2 edge-case)
                            EResult.OK
                        }
                        depotId to (result == EResult.OK)
                    }
                }.awaitAll()
                    .filter { it.second }
                    .map { it.first }
            }

            Timber.i("entitledDepotIds is empty? " + entitledDepotIds.isEmpty())

            if (entitledDepotIds.isEmpty()) return null

            Timber.i("Starting download for $appId")

            val info = DownloadInfo(entitledDepotIds.size).also { di ->
                di.setDownloadJob(instance!!.scope.launch {
                    coroutineScope {
                        entitledDepotIds.mapIndexed { idx, depotId ->
                            async {
                                depotGate.acquire()               // ── enter gate
                                var success = false
                                try {
                                    val MIN_INTERVAL_MS = 1000L
                                    var lastEmit = 0L
                                    Timber.i("Downloading game to " + defaultAppInstallPath)
                                    success = retry(times = 3, backoffMs = 2_000) {
                                        ContentDownloader(instance!!.steamClient!!)
                                            .downloadApp(
                                                appId         = appId,
                                                depotId       = depotId,
                                                installPath   = defaultAppInstallPath,
                                                stagingPath   = defaultAppStagingPath,
                                                branch        = branch,
                                                maxDownloads  = CHUNKS_PER_DEPOT,
                                                onDownloadProgress = { p ->
                                                    val now = SystemClock.elapsedRealtime()
                                                    if (now - lastEmit >= MIN_INTERVAL_MS || p >= 1f) {
                                                        lastEmit = now
                                                        di.setProgress(p, idx)
                                                    }
                                                },
                                                parentScope   = this,
                                            ).await()
                                    }
                                    if (success) di.setProgress(1f, idx)
                                    else {
                                        Timber.w("Depot $depotId skipped after retries")
                                        di.setWeight(idx, 0)
                                        di.setProgress(1f, idx)
                                    }
                                } finally {
                                    depotGate.release()
                                }
                            }
                        }.awaitAll()
                    }
                    downloadJobs.remove(appId)
                    // Write complete marker on disk
                    try {
                        val dir = File(getAppDirPath(appId))
                        dir.mkdirs()
                        File(dir, DOWNLOAD_COMPLETE_MARKER).createNewFile()
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to write download complete marker for $appId")
                    }
                })
            }

            downloadJobs[appId] = info
            var lastPercent = -1
            val sizes = entitledDepotIds.map { depotId ->
                val depot = getAppInfoOf(appId)!!.depots[depotId]!!

                val mInfo   = depot.manifests[branch]
                    ?: depot.encryptedManifests[branch]
                    ?: return@map 1L

                (mInfo.size ?: 1).toLong()         // Steam's VDF exposes this
            }
            sizes.forEachIndexed { i, bytes -> info.setWeight(i, bytes) }
            info.addProgressListener { p ->
                val percent = (p * 100).toInt()
                if (percent != lastPercent) {          // only when it really changed
                    lastPercent = percent
                }
            }
            return info
        }


        private suspend fun retry(
            times: Int,
            backoffMs: Long = 0,
            block: suspend () -> Boolean,
        ): Boolean {
            repeat(times - 1) { attempt ->
                if (block()) return true
                if (backoffMs > 0) delay(backoffMs * (attempt + 1))
            }
            return block()
        }


        fun getWindowsLaunchInfos(appId: Int): List<LaunchInfo> {
            return getAppInfoOf(appId)?.let { appInfo ->
                appInfo.config.launch.filter { launchInfo ->
                    // since configOS was unreliable and configArch was even more unreliable
                    launchInfo.executable.endsWith(".exe")
                }
            }.orEmpty()
        }

        suspend fun notifyRunningProcesses(vararg gameProcesses: GameProcessInfo) = withContext(Dispatchers.IO) {
            instance?.let { steamInstance ->
                val gamesPlayed = gameProcesses.mapNotNull { gameProcess ->
                    getAppInfoOf(gameProcess.appId)?.let { appInfo ->
                        getPkgInfoOf(gameProcess.appId)?.let { pkgInfo ->
                            appInfo.branches[gameProcess.branch]?.let { branch ->
                                val processId = gameProcess.processes
                                    .firstOrNull { it.parentIsSteam }
                                    ?.processId
                                    ?: gameProcess.processes.firstOrNull()?.processId
                                    ?: 0

                                val userAccountId = userSteamId!!.accountID.toInt()
                                GamePlayedInfo(
                                    gameId = gameProcess.appId.toLong(),
                                    processId = processId,
                                    ownerId = if (pkgInfo.ownerAccountId.contains(userAccountId)) {
                                        userAccountId
                                    } else {
                                        pkgInfo.ownerAccountId.first()
                                    },
                                    // TODO: figure out what this is and un-hardcode
                                    launchSource = 100,
                                    gameBuildId = branch.buildId.toInt(),
                                    processIdList = gameProcess.processes,
                                )
                            }
                        }
                    }
                }

                Timber.i(
                    "GameProcessInfo:%s",
                    gamesPlayed.joinToString("\n") { game ->
                        """
                        |   processId: ${game.processId}
                        |   gameId: ${game.gameId}
                        |   processes: ${
                            game.processIdList.joinToString("\n") { process ->
                                """
                                |   processId: ${process.processId}
                                |   processIdParent: ${process.processIdParent}
                                |   parentIsSteam: ${process.parentIsSteam}
                                """.trimMargin()
                            }
                        }
                        """.trimMargin()
                    },
                )

                steamInstance._steamApps?.notifyGamesPlayed(
                    gamesPlayed = gamesPlayed,
                    clientOsType = EOSType.AndroidUnknown,
                )
            }
        }

        fun beginLaunchApp(
            appId: Int,
            parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
            ignorePendingOperations: Boolean = false,
            preferredSave: SaveLocation = SaveLocation.None,
            prefixToPath: (String) -> String,
        ): Deferred<PostSyncInfo> = parentScope.async {
            if (syncInProgress) {
                Timber.w("Cannot launch app when sync already in progress")
                return@async PostSyncInfo(SyncResult.InProgress)
            }

            syncInProgress = true

            var syncResult = PostSyncInfo(SyncResult.UnknownFail)

            PrefManager.clientId?.let { clientId ->
                instance?.let { steamInstance ->
                    getAppInfoOf(appId)?.let { appInfo ->
                        steamInstance._steamCloud?.let { steamCloud ->
                            val postSyncInfo = SteamAutoCloud.syncUserFiles(
                                appInfo = appInfo,
                                clientId = clientId,
                                steamInstance = steamInstance,
                                steamCloud = steamCloud,
                                preferredSave = preferredSave,
                                parentScope = parentScope,
                                prefixToPath = prefixToPath,
                            ).await()

                            postSyncInfo?.let { info ->
                                syncResult = info

                                if (info.syncResult == SyncResult.Success || info.syncResult == SyncResult.UpToDate) {
                                    Timber.i(
                                        "Signaling app launch:\n\tappId: %d\n\tclientId: %s\n\tosType: %s",
                                        appId,
                                        PrefManager.clientId,
                                        EOSType.AndroidUnknown,
                                    )

                                    val pendingRemoteOperations = steamCloud.signalAppLaunchIntent(
                                        appId = appId,
                                        clientId = clientId,
                                        machineName = SteamUtils.getMachineName(steamInstance),
                                        ignorePendingOperations = ignorePendingOperations,
                                        osType = EOSType.AndroidUnknown,
                                    ).await()

                                    if (pendingRemoteOperations.isNotEmpty() && !ignorePendingOperations) {
                                        syncResult = PostSyncInfo(
                                            syncResult = SyncResult.PendingOperations,
                                            pendingRemoteOperations = pendingRemoteOperations,
                                        )
                                    } else if (ignorePendingOperations &&
                                        pendingRemoteOperations.any {
                                            it.operation == ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationAppSessionActive
                                        }
                                    ) {
                                        steamInstance._steamUser!!.kickPlayingSession()
                                    }
                                }
                            }
                        }
                    }
                }
            }

            syncInProgress = false

            return@async syncResult
        }

        suspend fun forceSyncUserFiles(
            appId: Int,
            prefixToPath: (String) -> String,
            preferredSave: SaveLocation = SaveLocation.None,
            parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
            overrideLocalChangeNumber: Long? = null,
        ): Deferred<PostSyncInfo> = parentScope.async {
            if (syncInProgress) {
                Timber.w("Cannot force sync when sync already in progress")
                return@async PostSyncInfo(SyncResult.InProgress)
            }

            syncInProgress = true

            var syncResult = PostSyncInfo(SyncResult.UnknownFail)

            PrefManager.clientId?.let { clientId ->
                instance?.let { steamInstance ->
                    getAppInfoOf(appId)?.let { appInfo ->
                        steamInstance._steamCloud?.let { steamCloud ->
                            val postSyncInfo = SteamAutoCloud.syncUserFiles(
                                appInfo = appInfo,
                                clientId = clientId,
                                steamInstance = steamInstance,
                                steamCloud = steamCloud,
                                preferredSave = preferredSave,
                                parentScope = parentScope,
                                prefixToPath = prefixToPath,
                                overrideLocalChangeNumber = overrideLocalChangeNumber,
                            ).await()

                            postSyncInfo?.let { info ->
                                syncResult = info
                                Timber.i("Force cloud sync completed for app $appId with result: ${info.syncResult}")
                            }
                        }
                    }
                }
            }

            syncInProgress = false

            return@async syncResult
        }

        suspend fun closeApp(appId: Int, prefixToPath: (String) -> String) = withContext(Dispatchers.IO) {
            async {
                if (syncInProgress) {
                    Timber.w("Cannot close app when sync already in progress")
                    return@async
                }

                syncInProgress = true

                PrefManager.clientId?.let { clientId ->
                    instance?.let { steamInstance ->
                        getAppInfoOf(appId)?.let { appInfo ->
                            steamInstance._steamCloud?.let { steamCloud ->
                                val postSyncInfo = SteamAutoCloud.syncUserFiles(
                                    appInfo = appInfo,
                                    clientId = clientId,
                                    steamInstance = steamInstance,
                                    steamCloud = steamCloud,
                                    parentScope = this,
                                    prefixToPath = prefixToPath,
                                ).await()

                                steamCloud.signalAppExitSyncDone(
                                    appId = appId,
                                    clientId = clientId,
                                    uploadsCompleted = postSyncInfo?.uploadsCompleted == true,
                                    uploadsRequired = postSyncInfo?.uploadsRequired == false,
                                )
                            }
                        }
                    }
                }

                syncInProgress = false
            }
        }

        data class FileChanges(
            val filesDeleted: List<UserFileInfo>,
            val filesModified: List<UserFileInfo>,
            val filesCreated: List<UserFileInfo>,
        )

        private fun login(
            username: String,
            accessToken: String? = null,
            refreshToken: String? = null,
            password: String? = null,
            rememberSession: Boolean = false,
            twoFactorAuth: String? = null,
            emailAuth: String? = null,
            clientId: Long? = null,
        ) {
            val steamUser = instance!!._steamUser!!

            // Sensitive info, only print in DEBUG build.
//            if (BuildConfig.DEBUG) {
//                Timber.d(
//                    """
//                    Login Information:
//                     Username: $username
//                     AccessToken: $accessToken
//                     RefreshToken: $refreshToken
//                     Password: $password
//                     Remember Session: $rememberSession
//                     TwoFactorAuth: $twoFactorAuth
//                     EmailAuth: $emailAuth
//                    """.trimIndent(),
//                )
//            }

            PrefManager.username = username

            if ((password != null && rememberSession) || refreshToken != null) {
                if (accessToken != null) {
                    PrefManager.accessToken = accessToken
                }

                if (refreshToken != null) {
                    PrefManager.refreshToken = refreshToken
                }

                if (clientId != null) {
                    PrefManager.clientId = clientId
                }
            }

            val event = SteamEvent.LogonStarted(username)
            PluviaApp.events.emit(event)

            steamUser.logOn(
                LogOnDetails(
                    username = SteamUtils.removeSpecialChars(username).trim(),
                    password = password?.let { SteamUtils.removeSpecialChars(it).trim() },
                    shouldRememberPassword = rememberSession,
                    twoFactorCode = twoFactorAuth,
                    authCode = emailAuth,
                    accessToken = refreshToken,
                    loginID = SteamUtils.getUniqueDeviceId(instance!!),
                    machineName = SteamUtils.getMachineName(instance!!),
                    chatMode = ChatMode.NEW_STEAM_CHAT,
                ),
            )
        }

        suspend fun startLoginWithCredentials(
            username: String,
            password: String,
            rememberSession: Boolean,
            authenticator: IAuthenticator,
        ) = withContext(Dispatchers.IO) {
            try {
                Timber.i("Logging in via credentials.")
                instance!!._loginResult = LoginResult.InProgress
                Timber.i("Set login result to InProgress.")
                instance!!.steamClient?.let { steamClient ->
                    val authDetails = AuthSessionDetails().apply {
                        this.username = username.trim()
                        this.password = password.trim()
                        this.persistentSession = rememberSession
                        this.authenticator = authenticator
                        this.deviceFriendlyName = SteamUtils.getMachineName(instance!!)
                    }

                    val event = SteamEvent.LogonStarted(username)
                    PluviaApp.events.emit(event)

                    val authSession = steamClient.authentication.beginAuthSessionViaCredentials(authDetails).await()

                    val pollResult = authSession.pollingWaitForResult().await()

                    if (pollResult.accountName.isEmpty() && pollResult.refreshToken.isEmpty()) {
                        throw Exception("No account name or refresh token received.")
                    }

                    login(
                        clientId = authSession.clientID,
                        username = pollResult.accountName,
                        accessToken = pollResult.accessToken,
                        refreshToken = pollResult.refreshToken,
                        rememberSession = rememberSession,
                    )
                } ?: run {
                    Timber.e("Could not logon: Failed to connect to Steam")

                    val event = SteamEvent.LogonEnded(username, LoginResult.Failed, "No connection to Steam")
                    PluviaApp.events.emit(event)
                }
            } catch (e: Exception) {
                Timber.e(e, "Login failed")

                val message = when (e) {
                    is CancellationException -> "Unknown cancellation"
                    is AuthenticationException -> e.result?.name ?: e.message
                    else -> e.message ?: e.javaClass.name
                }

                val event = SteamEvent.LogonEnded(username, LoginResult.Failed, message)
                PluviaApp.events.emit(event)
            }
        }

        suspend fun startLoginWithQr() = withContext(Dispatchers.IO) {
            try {
                Timber.i("Logging in via QR.")

                instance!!.steamClient?.let { steamClient ->
                    isWaitingForQRAuth = true

                    val authDetails = AuthSessionDetails().apply {
                        deviceFriendlyName = SteamUtils.getMachineName(instance!!)
                    }

                    val authSession = steamClient.authentication.beginAuthSessionViaQR(authDetails).await()

                    // Steam will periodically refresh the challenge url, this callback allows you to draw a new qr code.
                    authSession.challengeUrlChanged = instance

                    val qrEvent = SteamEvent.QrChallengeReceived(authSession.challengeUrl)
                    PluviaApp.events.emit(qrEvent)

                    Timber.d("PollingInterval: ${authSession.pollingInterval.toLong()}")

                    var authPollResult: AuthPollResult? = null

                    while (isWaitingForQRAuth && authPollResult == null) {
                        try {
                            authPollResult = authSession.pollAuthSessionStatus().await()
                        } catch (e: Exception) {
                            Timber.e(e, "Poll auth session status error")
                            throw e
                        }

                        // Sensitive info, only print in DEBUG build.
//                        if (BuildConfig.DEBUG && authPollResult != null) {
//                            Timber.d(
//                                "AccessToken: %s\nAccountName: %s\nRefreshToken: %s\nNewGuardData: %s",
//                                authPollResult.accessToken,
//                                authPollResult.accountName,
//                                authPollResult.refreshToken,
//                                authPollResult.newGuardData ?: "No new guard data",
//                            )
//                        }

                        delay(authSession.pollingInterval.toLong())
                    }

                    isWaitingForQRAuth = false

                    val event = SteamEvent.QrAuthEnded(authPollResult != null)
                    PluviaApp.events.emit(event)

                    // there is a chance qr got cancelled and there is no authPollResult
                    if (authPollResult == null) {
                        Timber.e("Got no auth poll result")
                        throw Exception("Got no auth poll result")
                    }

                    login(
                        clientId = authSession.clientID,
                        username = authPollResult.accountName,
                        accessToken = authPollResult.accessToken,
                        refreshToken = authPollResult.refreshToken,
                    )
                } ?: run {
                    Timber.e("Could not start QR logon: Failed to connect to Steam")

                    val event = SteamEvent.QrAuthEnded(success = false, message = "No connection to Steam")
                    PluviaApp.events.emit(event)
                }
            } catch (e: Exception) {
                Timber.e(e, "QR failed")

                val message = when (e) {
                    is CancellationException -> "QR Session timed out"
                    is AuthenticationException -> e.result?.name ?: e.message
                    else -> e.message ?: e.javaClass.name
                }

                val event = SteamEvent.QrAuthEnded(success = false, message = message)
                PluviaApp.events.emit(event)
            }
        }

        fun stopLoginWithQr() {
            Timber.i("Stopping QR polling")

            isWaitingForQRAuth = false
        }

        fun stop() {
            instance?.let { steamInstance ->
                steamInstance.scope.launch {
                    steamInstance.stop()
                }
            }
        }

        fun logOut() {
            CoroutineScope(Dispatchers.Default).launch {
                // isConnected = false

                isLoggingOut = true

                performLogOffDuties()

                val steamUser = instance!!._steamUser!!
                steamUser.logOff()
            }
        }

        private fun clearUserData() {
            PrefManager.clearPreferences()

            clearDatabase()
        }

        fun clearDatabase() {
            with(instance!!) {
                scope.launch {
                    db.withTransaction {
                        db.emoticonDao().deleteAll()
                        db.friendMessagesDao().deleteAllMessages()
                        appDao.deleteAll()
                        changeNumbersDao.deleteAll()
                        fileChangeListsDao.deleteAll()
                        friendDao.deleteAll()
                        licenseDao.deleteAll()
                    }
                }
            }
        }

        private fun performLogOffDuties() {
            val username = PrefManager.username

            clearUserData()

            val event = SteamEvent.LoggedOut(username)
            PluviaApp.events.emit(event)
        }

        suspend fun getEmoticonList() = withContext(Dispatchers.IO) {
            instance?.steamClient!!.getHandler<PluviaHandler>()!!.getEmoticonList()
        }

        suspend fun fetchEmoticons(): List<Emoticon> = withContext(Dispatchers.IO) {
            instance?.emoticonDao!!.getAllAsList()
        }

        suspend fun getProfileInfo(friendID: SteamID): ProfileInfoCallback = withContext(Dispatchers.IO) {
            instance?._steamFriends!!.requestProfileInfo(friendID).await()
        }

        suspend fun getOwnedGames(friendID: Long): List<OwnedGames> = withContext(Dispatchers.IO) {
            instance?._unifiedFriends!!.getOwnedGames(friendID)
        }

        suspend fun getRecentMessages(friendID: Long) = withContext(Dispatchers.IO) {
            instance?._unifiedFriends!!.getRecentMessages(friendID)
        }

        suspend fun ackMessage(friendID: Long) = withContext(Dispatchers.IO) {
            instance?._unifiedFriends!!.ackMessage(friendID)
        }

        suspend fun requestAliasHistory(friendID: Long) = withContext(Dispatchers.IO) {
            instance?.steamClient!!.getHandler<SteamFriends>()?.requestAliasHistory(SteamID(friendID))
        }

        suspend fun sendTypingMessage(friendID: Long) = withContext(Dispatchers.IO) {
            instance?._unifiedFriends!!.setIsTyping(friendID)
        }

        suspend fun sendMessage(friendID: Long, message: String) = withContext(Dispatchers.IO) {
            instance?._unifiedFriends!!.sendMessage(friendID, message)
        }

        suspend fun blockFriend(friendID: Long) = withContext(Dispatchers.IO) {
            val friend = SteamID(friendID)
            val result = instance?._steamFriends!!.ignoreFriend(friend).await()

            if (result.result == EResult.OK) {
                val blockedFriend = instance!!.friendDao.findFriend(friendID)
                blockedFriend?.let {
                    instance?.friendDao!!.update(it.copy(relation = EFriendRelationship.Blocked))
                }
            }
        }

        suspend fun removeFriend(friendID: Long) = withContext(Dispatchers.IO) {
            val friend = SteamID(friendID)
            instance?._steamFriends!!.removeFriend(friend)
            instance?.friendDao!!.remove(friendID)
        }

        suspend fun setNickName(friendID: Long, value: String) = withContext(Dispatchers.IO) {
            val friend = SteamID(friendID)
            instance?._steamFriends!!.setFriendNickname(friend, value)
        }

        // Add helper to detect if any downloads or cloud sync are in progress
        fun hasActiveOperations(): Boolean {
            return syncInProgress || downloadJobs.values.any { it.getProgress() < 1f }
        }

        // Should service auto-stop when idle (backgrounded)?
        var autoStopWhenIdle: Boolean = false

        suspend fun isUpdatePending(
            appId: Int,
            branch: String = "public",
        ): Boolean = withContext(Dispatchers.IO) {
            val steamApps = instance?._steamApps ?: return@withContext false

            // ── 1. Fetch the latest app header from Steam (PICS).
            val pics = steamApps.picsGetProductInfo(
                apps = listOf(PICSRequest(id = appId)),
                packages = emptyList(),
            ).await()

            val remoteAppInfo = pics.results
                .firstOrNull()
                ?.apps
                ?.values
                ?.firstOrNull()
                ?: return@withContext false          // nothing returned ⇒ treat as up-to-date

            val remoteSteamApp = remoteAppInfo.keyValues.generateSteamApp()
            val localSteamApp  = getAppInfoOf(appId) ?: return@withContext true // not cached yet

            // ── 2. Compare manifest IDs of the depots we actually install.
            getDownloadableDepots(appId).keys.any { depotId ->
                val remoteManifest = remoteSteamApp.depots[depotId]?.manifests?.get(branch)
                val localManifest  =  localSteamApp .depots[depotId]?.manifests?.get(branch)
                remoteManifest?.gid != localManifest?.gid
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // JavaSteam logger CME hot-fix
        runCatching {
            val clazz = Class.forName("in.dragonbra.javasteam.util.log.LogManager")
            val field = clazz.getDeclaredField("LOGGERS").apply { isAccessible = true }
            field.set(
                /* obj = */ null,
                java.util.concurrent.ConcurrentHashMap<Any, Any>()   // replaces the HashMap
            )
        }

        PluviaApp.events.on<AndroidEvent.EndProcess, Unit>(onEndProcess)

        notificationHelper = NotificationHelper(applicationContext)
        // Setup Wi-Fi connectivity monitoring for download-on-WiFi-only
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // Determine initial Wi-Fi state
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        isWifiConnected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        // Register callback for Wi-Fi connectivity
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Timber.d("Wifi available")
                isWifiConnected = true
            }
            override fun onLost(network: Network) {
                Timber.d("Wifi lost")
                isWifiConnected = false
                if (PrefManager.downloadOnWifiOnly) {
                    // Pause all ongoing downloads
                    for ((_, info) in downloadJobs) {
                        Timber.d("Cancelling job")
                        info.cancel()
                    }
                    downloadJobs.clear()
                    notificationHelper.notify("Download paused – waiting for Wi-Fi")
                }
            }
        }
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        // To view log messages in android logcat properly
        val logger = object : LogListener {
            override fun onLog(clazz: Class<*>, message: String?, throwable: Throwable?) {
                val logMessage = message ?: "No message given"
                Timber.i(throwable, "[${clazz.simpleName}] -> $logMessage")
            }

            override fun onError(clazz: Class<*>, message: String?, throwable: Throwable?) {
                val logMessage = message ?: "No message given"
                Timber.e(throwable, "[${clazz.simpleName}] -> $logMessage")
            }
        }
        LogManager.addListener(logger)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Notification intents
        when (intent?.action) {
            NotificationHelper.ACTION_EXIT -> {
                Timber.d("Exiting app via notification intent")

                val event = AndroidEvent.EndProcess
                PluviaApp.events.emit(event)

                return START_NOT_STICKY
            }
        }

        if (!isRunning) {
            Timber.i("Using server list path: $serverListPath")

            val configuration = SteamConfiguration.create {
                it.withProtocolTypes(PROTOCOL_TYPES)
                it.withCellID(PrefManager.cellId)
                it.withServerListProvider(FileServerListProvider(File(serverListPath)))
                it.withManifestProvider(ThreadSafeManifestProvider(File(depotManifestsPath).toPath()))
                it.withConnectionTimeout(60000L)
            }

            // create our steam client instance
            steamClient = SteamClient(configuration).apply {
                addHandler(PluviaHandler())

                // remove callbacks we're not using.
                removeHandler(SteamGameServer::class.java)
                removeHandler(SteamMasterServer::class.java)
                removeHandler(SteamWorkshop::class.java)
                removeHandler(SteamScreenshots::class.java)
                removeHandler(SteamUserStats::class.java)
            }

            // create the callback manager which will route callbacks to function calls
            callbackManager = CallbackManager(steamClient!!)

            // get the different handlers to be used throughout the service
            _steamUser = steamClient!!.getHandler(SteamUser::class.java)
            _steamApps = steamClient!!.getHandler(SteamApps::class.java)
            _steamFriends = steamClient!!.getHandler(SteamFriends::class.java)
            _steamCloud = steamClient!!.getHandler(SteamCloud::class.java)

            _unifiedFriends = SteamUnifiedFriends(this)
            _steamFamilyGroups = steamClient!!.getHandler<SteamUnifiedMessages>()!!.createService<FamilyGroups>()

            // subscribe to the callbacks we are interested in
            with(callbackSubscriptions) {
                with(callbackManager!!) {
                    add(subscribe(ConnectedCallback::class.java, ::onConnected))
                    add(subscribe(DisconnectedCallback::class.java, ::onDisconnected))
                    add(subscribe(LoggedOnCallback::class.java, ::onLoggedOn))
                    add(subscribe(LoggedOffCallback::class.java, ::onLoggedOff))
                    add(subscribe(PersonaStateCallback::class.java, ::onPersonaStateReceived))
                    add(subscribe(LicenseListCallback::class.java, ::onLicenseList))
                    add(subscribe(NicknameListCallback::class.java, ::onNicknameList))
                    add(subscribe(FriendsListCallback::class.java, ::onFriendsList))
                    add(subscribe(EmoticonListCallback::class.java, ::onEmoticonList))
                    add(subscribe(AliasHistoryCallback::class.java, ::onAliasHistory))
                }
            }

            isRunning = true

            // we should use Dispatchers.IO here since we are running a sleeping/blocking function
            // "The idea is that the IO dispatcher spends a lot of time waiting (IO blocked),
            // while the Default dispatcher is intended for CPU intensive tasks, where there
            // is little or no sleep."
            // source: https://stackoverflow.com/a/59040920
            scope.launch {
                while (isRunning) {
                    // logD("runWaitCallbacks")

                    try {
                        callbackManager!!.runWaitCallbacks(1000L)
                    } catch (e: Exception) {
                        Timber.e("runWaitCallbacks failed: $e")
                    }
                }
            }

            connectToSteam()
        }

        val notification = notificationHelper.createForegroundNotification("Starting up...")
        startForeground(1, notification)

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationHelper.cancel()

        // Unregister Wi-Fi connectivity callback
        connectivityManager.unregisterNetworkCallback(networkCallback)

        scope.launch { stop() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun connectToSteam() {
        CoroutineScope(Dispatchers.Default).launch {
            // this call errors out if run on the main thread
            steamClient!!.connect()

            delay(5000)

            if (!isConnected) {
                Timber.w("Failed to connect to Steam, marking endpoint bad and force disconnecting")

                try {
                    steamClient!!.servers.tryMark(steamClient!!.currentEndpoint, PROTOCOL_TYPES, ServerQuality.BAD)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to mark endpoint as bad:")
                }

                try {
                    steamClient!!.disconnect()
                } catch (e: Exception) {
                    Timber.e(e, "There was an issue when disconnecting:")
                }
            }
        }
    }

    private suspend fun stop() {
        Timber.i("Stopping Steam service")
        if (steamClient != null && steamClient!!.isConnected) {
            isStopping = true

            steamClient!!.disconnect()

            while (isStopping) {
                delay(200L)
            }

            // the reason we don't clearValues() here is because the onDisconnect
            // callback does it for us
        } else {
            clearValues()
        }
    }

    private fun clearValues() {
        _loginResult = LoginResult.Failed
        isRunning = false
        isConnected = false
        isLoggingOut = false
        isWaitingForQRAuth = false

        steamClient = null
        _steamUser = null
        _steamApps = null
        _steamFriends = null
        _steamCloud = null

        callbackSubscriptions.forEach { it.close() }
        callbackSubscriptions.clear()
        callbackManager = null

        _unifiedFriends?.close()
        _unifiedFriends = null

        isStopping = false
        retryAttempt = 0

        PluviaApp.events.off<AndroidEvent.EndProcess, Unit>(onEndProcess)
        PluviaApp.events.clearAllListenersOf<SteamEvent<Any>>()
    }

    private fun reconnect() {
        notificationHelper.notify("Retrying...")

        isConnected = false

        val event = SteamEvent.Disconnected
        PluviaApp.events.emit(event)

        steamClient!!.disconnect()
    }

    // region [REGION] callbacks
    @Suppress("UNUSED_PARAMETER", "unused")
    private fun onConnected(callback: ConnectedCallback) {
        Timber.i("Connected to Steam")

        retryAttempt = 0
        isConnected = true

        var isAutoLoggingIn = false

        if (PrefManager.username.isNotEmpty() && PrefManager.refreshToken.isNotEmpty()) {
            isAutoLoggingIn = true

            login(
                username = PrefManager.username,
                refreshToken = PrefManager.refreshToken,
                rememberSession = true,
            )
        }

        val event = SteamEvent.Connected(isAutoLoggingIn)
        PluviaApp.events.emit(event)
    }

    private fun onDisconnected(callback: DisconnectedCallback) {
        Timber.i("Disconnected from Steam. User initiated: ${callback.isUserInitiated}")

        isConnected = false

        if (!isStopping && retryAttempt < MAX_RETRY_ATTEMPTS) {
            retryAttempt++

            Timber.w("Attempting to reconnect (retry $retryAttempt)")

            // isLoggingOut = false
            val event = SteamEvent.RemotelyDisconnected
            PluviaApp.events.emit(event)

            connectToSteam()
        } else {
            val event = SteamEvent.Disconnected
            PluviaApp.events.emit(event)

            clearValues()

            stopSelf()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun onLoggedOn(callback: LoggedOnCallback) {
        Timber.i("Logged onto Steam: ${callback.result}")

        when (callback.result) {
            EResult.TryAnotherCM -> {
                _loginResult = LoginResult.Failed
                reconnect()
            }

            EResult.OK -> {
                // save the current cellid somewhere. if we lose our saved server list, we can use this when retrieving
                // servers from the Steam Directory.
                if (!PrefManager.cellIdManuallySet) {
                    PrefManager.cellId = callback.cellID
                }

                // retrieve persona data of logged in user
                scope.launch { requestUserPersona() }

                // Request family share info if we have a familyGroupId.
                if (callback.familyGroupId != 0L) {
                    scope.launch {
                        val request = SteammessagesFamilygroupsSteamclient.CFamilyGroups_GetFamilyGroup_Request.newBuilder().apply {
                            familyGroupid = callback.familyGroupId
                        }.build()

                        _steamFamilyGroups!!.getFamilyGroup(request).await().let {
                            if (it.result != EResult.OK) {
                                Timber.w("An error occurred loading family group info.")
                                return@launch
                            }

                            val response = it.body

                            Timber.i("Found family share: ${response.name}, with ${response.membersCount} members.")

                            response.membersList.forEach { member ->
                                val accountID = SteamID(member.steamid).accountID.toInt()
                                familyGroupMembers.add(accountID)
                            }
                        }
                    }
                }

                // continuously check for pics changes
                continuousPICSChangesChecker()

                // request app pics data when needed
                continuousPICSGetProductInfo()

                if (false) {
                    // No social features are implemented at present
                    // continuously check for game names that friends are playing.
                    continuousFriendChecker()
                }

                // Tell steam we're online, this allows friends to update.
                _steamFriends?.setPersonaState(PrefManager.personaState)

                notificationHelper.notify("Connected")

                _loginResult = LoginResult.Success
            }

            else -> {
                clearUserData()

                _loginResult = LoginResult.Failed

                reconnect()
            }
        }

        val event = SteamEvent.LogonEnded(PrefManager.username, _loginResult)
        PluviaApp.events.emit(event)
    }

    private fun onLoggedOff(callback: LoggedOffCallback) {
        Timber.i("Logged off of Steam: ${callback.result}")

        notificationHelper.notify("Disconnected...")

        if (isLoggingOut || callback.result == EResult.LogonSessionReplaced) {
            performLogOffDuties()

            scope.launch { stop() }
        } else if (callback.result == EResult.LoggedInElsewhere) {
            // received when a client runs an app and wants to forcibly close another
            // client running an app
            val event = SteamEvent.ForceCloseApp
            PluviaApp.events.emit(event)

            reconnect()
        } else {
            reconnect()
        }
    }

    private fun onNicknameList(callback: NicknameListCallback) {
        Timber.d("Nickname list called: ${callback.nicknames.size}")
        scope.launch {
            db.withTransaction {
                friendDao.clearAllNicknames()
                friendDao.updateNicknames(callback.nicknames)
            }
        }
    }

    private fun onFriendsList(callback: FriendsListCallback) {
        Timber.d("onFriendsList ${callback.friendList.size}")
        scope.launch {
            db.withTransaction {
                val friendsToInsert = mutableListOf<SteamFriend>()
                val friendsToUpdate = mutableListOf<SteamFriend>()
                callback.friendList
                    .filter { it.steamID.isIndividualAccount }
                    .forEach { filteredFriend ->
                        val friendId = filteredFriend.steamID.convertToUInt64()
                        val friend = friendDao.findFriend(friendId)

                        if (friend == null) {
                            SteamFriend(id = friendId, relation = filteredFriend.relationship).also(friendsToInsert::add)
                            // Not in the DB, create them.
                            val friendToAdd = SteamFriend(
                                id = filteredFriend.steamID.convertToUInt64(),
                                relation = filteredFriend.relationship,
                            )

                            friendDao.insert(friendToAdd)
                        } else {
                            friend.copy(relation = filteredFriend.relationship).also(friendsToUpdate::add)
                            // In the DB, update them.
                            val dbFriend = friend.copy(relation = filteredFriend.relationship)
                            friendDao.update(dbFriend)
                        }
                    }
                if (friendsToInsert.isNotEmpty()) {
                    friendDao.insertAll(friendsToInsert)
                }
                if (friendsToUpdate.isNotEmpty()) {
                    friendDao.updateAll(friendsToUpdate)
                }

                // Add logged in account if we don't exist yet.
                val selfId = userSteamId!!.convertToUInt64()
                val self = friendDao.findFriend(selfId)

                if (self == null) {
                    val sid = SteamFriend(id = selfId)
                    friendDao.insert(sid)
                }
            }

            // NOTE: Our UI could load too quickly on fresh database, our icon will be "?"
            //  unless relaunched or we nav to a new screen.
            _unifiedFriends?.refreshPersonaStates()
        }
    }

    private fun onEmoticonList(callback: EmoticonListCallback) {
        Timber.i("Getting emotes and stickers, size: ${callback.emoteList.size}")
        scope.launch {
            db.withTransaction {
                emoticonDao.replaceAll(callback.emoteList)
            }
        }
    }

    private fun onAliasHistory(callback: AliasHistoryCallback) {
        val names = callback.responses.flatMap { map -> map.names }.map { map -> map.name }
        val event = SteamEvent.OnAliasHistory(names)
        PluviaApp.events.emit(event)
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun onPersonaStateReceived(callback: PersonaStateCallback) {
        // Ignore accounts that arent individuals
        if (!callback.friendID.isIndividualAccount) {
            return
        }

        // Ignore states where the name is blank.
        if (callback.name.isEmpty()) {
            return
        }

        // Timber.d("Persona state received: ${callback.name}")

        scope.launch {
            db.withTransaction {
                val id = callback.friendID.convertToUInt64()
                val friend = friendDao.findFriend(id)

                if (friend == null) {
                    Timber.w("onPersonaStateReceived: failed to find friend to update: $id")
                    return@withTransaction
                }

                friendDao.update(
                    friend.copy(
                        statusFlags = callback.statusFlags,
                        state = callback.state,
                        stateFlags = callback.stateFlags,
                        gameAppID = callback.gameAppID,
                        gameID = callback.gameID,
                        gameName = appDao.findApp(callback.gameAppID)?.name ?: callback.gameName,
                        gameServerIP = NetHelpers.getIPAddress(callback.gameServerIP),
                        gameServerPort = callback.gameServerPort,
                        queryPort = callback.queryPort,
                        sourceSteamID = callback.sourceSteamID,
                        gameDataBlob = callback.gameDataBlob.decodeToString(),
                        name = callback.name,
                        avatarHash = callback.avatarHash.toHexString(),
                        lastLogOff = callback.lastLogOff,
                        lastLogOn = callback.lastLogOn,
                        clanRank = callback.clanRank,
                        clanTag = callback.clanTag,
                        onlineSessionInstances = callback.onlineSessionInstances,
                    ),
                )

                // Send off an event if we change states.
                if (callback.friendID == steamClient!!.steamID) {
                    friendDao.findFriend(id)?.let { account ->
                        val event = SteamEvent.PersonaStateReceived(account)
                        PluviaApp.events.emit(event)
                    }
                }
            }
        }
    }

    private fun onLicenseList(callback: LicenseListCallback) {
        if (callback.result != EResult.OK) {
            Timber.w("Failed to get License list")
            return
        }

        Timber.i("Received License List ${callback.result}, size: ${callback.licenseList.size}")

        scope.launch {
            db.withTransaction {
                // Note: I assume with every launch we do, in fact, update the licenses for app the apps if we join or get removed
                //      from family sharing... We really can't test this as there is a 1-year cooldown.
                //      Then 'findStaleLicences' will find these now invalid items to remove.
                val licensesToAdd = callback.licenseList
                    .groupBy { it.packageID }
                    .map { licensesEntry ->
                        val preferred = licensesEntry.value.firstOrNull {
                            it.ownerAccountID == userSteamId?.accountID?.toInt()
                        } ?: licensesEntry.value.first()
                        SteamLicense(
                            packageId = licensesEntry.key,
                            lastChangeNumber = preferred.lastChangeNumber,
                            timeCreated = preferred.timeCreated,
                            timeNextProcess = preferred.timeNextProcess,
                            minuteLimit = preferred.minuteLimit,
                            minutesUsed = preferred.minutesUsed,
                            paymentMethod = preferred.paymentMethod,
                            licenseFlags = licensesEntry.value
                                .map { it.licenseFlags }
                                .reduceOrNull { first, second ->
                                    val combined = EnumSet.copyOf(first)
                                    combined.addAll(second)
                                    combined
                                } ?: EnumSet.noneOf(ELicenseFlags::class.java),
                            purchaseCode = preferred.purchaseCode,
                            licenseType = preferred.licenseType,
                            territoryCode = preferred.territoryCode,
                            accessToken = preferred.accessToken,
                            ownerAccountId = licensesEntry.value.map { it.ownerAccountID }, // Read note above
                            masterPackageID = preferred.masterPackageID,
                        )
                    }

                if (licensesToAdd.isNotEmpty()) {
                    Timber.i("Adding ${licensesToAdd.size} licenses")
                    licenseDao.insertAll(licensesToAdd)
                }

                val licensesToRemove = licenseDao.findStaleLicences(
                    packageIds = callback.licenseList.map { it.packageID },
                )
                if (licensesToRemove.isNotEmpty()) {
                    Timber.i("Removing ${licensesToRemove.size} (stale) licenses")
                    val packageIds = licensesToRemove.map { it.packageId }
                    licenseDao.deleteStaleLicenses(packageIds)
                }

                // Get PICS information with the current license database.
                licenseDao.getAllLicenses()
                    .map { PICSRequest(it.packageId, it.accessToken) }
                    .chunked(MAX_PICS_BUFFER)
                    .forEach { chunk ->
                        Timber.d("onLicenseList: Queueing ${chunk.size} package(s) for PICS")
                        packagePicsChannel.send(chunk)
                    }
            }
        }
    }

    override fun onChanged(qrAuthSession: QrAuthSession?) {
        qrAuthSession?.let { qr ->
            if (!BuildConfig.DEBUG) {
                Timber.d("QR code changed -> ${qr.challengeUrl}")
            }

            val event = SteamEvent.QrChallengeReceived(qr.challengeUrl)
            PluviaApp.events.emit(event)
        } ?: run { Timber.w("QR challenge url was null") }
    }
    // endregion

    /**
     * Request changes for apps and packages since a given change number.
     * Checks every [PICS_CHANGE_CHECK_DELAY] seconds.
     * Results are returned in a [PICSChangesCallback]
     */
    private fun continuousPICSChangesChecker() = scope.launch {
        while (isActive && isLoggedIn) {
            // Initial delay before each check
            delay(60.seconds)

            try {
                val changesSince = _steamApps!!.picsGetChangesSince(
                    lastChangeNumber = PrefManager.lastPICSChangeNumber,
                    sendAppChangeList = true,
                    sendPackageChangelist = true,
                ).await()

                if (PrefManager.lastPICSChangeNumber == changesSince.currentChangeNumber) {
                    Timber.w("Change number was the same as last change number, skipping")
                    continue
                }

                // Set our last change number
                PrefManager.lastPICSChangeNumber = changesSince.currentChangeNumber

                Timber.d(
                    "picsGetChangesSince:" +
                            "\n\tlastChangeNumber: ${changesSince.lastChangeNumber}" +
                            "\n\tcurrentChangeNumber: ${changesSince.currentChangeNumber}" +
                            "\n\tisRequiresFullUpdate: ${changesSince.isRequiresFullUpdate}" +
                            "\n\tisRequiresFullAppUpdate: ${changesSince.isRequiresFullAppUpdate}" +
                            "\n\tisRequiresFullPackageUpdate: ${changesSince.isRequiresFullPackageUpdate}" +
                            "\n\tappChangesCount: ${changesSince.appChanges.size}" +
                            "\n\tpkgChangesCount: ${changesSince.packageChanges.size}",

                    )

                // Process any app changes
                launch {
                    changesSince.appChanges.values
                        .filter { changeData ->
                            // only queue PICS requests for apps existing in the db that have changed
                            val app = appDao.findApp(changeData.id) ?: return@filter false
                            changeData.changeNumber != app.lastChangeNumber
                        }
                        .map { PICSRequest(id = it.id) }
                        .chunked(MAX_PICS_BUFFER)
                        .forEach { chunk ->
                            Timber.d("onPicsChanges: Queueing ${chunk.size} app(s) for PICS")
                            appPicsChannel.send(chunk)
                        }
                }

                // Process any package changes
                launch {
                    val pkgsWithChanges = changesSince.packageChanges.values
                        .filter { changeData ->
                            // only queue PICS requests for pkgs existing in the db that have changed
                            val pkg = licenseDao.findLicense(changeData.id) ?: return@filter false
                            changeData.changeNumber != pkg.lastChangeNumber
                        }

                    if (pkgsWithChanges.isNotEmpty()) {
                        val pkgsForAccessTokens = pkgsWithChanges.filter { it.isNeedsToken }.map { it.id }

                        val accessTokens = _steamApps?.picsGetAccessTokens(emptyList(), pkgsForAccessTokens)
                            ?.await()?.packageTokens ?: emptyMap()

                        pkgsWithChanges
                            .map { PICSRequest(it.id, accessTokens[it.id] ?: 0) }
                            .chunked(MAX_PICS_BUFFER)
                            .forEach { chunk ->
                                Timber.d("onPicsChanges: Queueing ${chunk.size} package(s) for PICS")
                                packagePicsChannel.send(chunk)
                            }
                    }
                }
            } catch (e: NullPointerException) {
                Timber.w("No lastPICSChangeNumber, skipping")
                continue
            }
        }
    }

    /**
     * Continuously check for friends playing games and query for pics if its a game we don't have in the database.
     */
    private fun continuousFriendChecker() = scope.launch {
        val friendsToUpdate = mutableListOf<SteamFriend>()
        val gameRequest = mutableListOf<PICSRequest>()
        while (isActive && isLoggedIn) {
            // Initial delay before each check
            delay(20.seconds)

            friendsToUpdate.clear()
            gameRequest.clear()

            val friendsInGame = friendDao.findFriendsInGame()

            Timber.d("Found ${friendsInGame.size} friends in game")

            friendsInGame.forEach { friend ->
                val app = appDao.findApp(friend.gameAppID)
                if (app != null) {
                    if (friend.gameName != app.name) {
                        Timber.d("Updating ${friend.name} with game ${app.name}")
                        friendsToUpdate.add(friend.copy(gameName = app.name))
                    }
                } else {
                    // Didn't find the app, we'll get it next time.
                    gameRequest.add(PICSRequest(id = friend.gameAppID))
                }
            }

            if (friendsToUpdate.isNotEmpty()) {
                db.withTransaction {
                    friendDao.updateAll(friendsToUpdate)
                }
            }

            gameRequest
                .chunked(MAX_PICS_BUFFER)
                .forEach { chunk ->
                    Timber.d("continuousFriendChecker: Queueing ${chunk.size} app(s) for PICS")
                    appPicsChannel.send(chunk)
                }
        }
    }

    /**
     * A buffered flow to parse so many PICS requests in a given moment.
     */
    private fun continuousPICSGetProductInfo() {
        scope.launch {
            appPicsChannel.receiveAsFlow()
                .filter { it.isNotEmpty() }
                .buffer(capacity = MAX_PICS_BUFFER, onBufferOverflow = BufferOverflow.SUSPEND)
                .collect { appRequests ->
                    Timber.d("Processing ${appRequests.size} app PICS requests")

                    val callback = _steamApps!!.picsGetProductInfo(
                        apps = appRequests,
                        packages = emptyList(),
                    ).await()

                    callback.results.forEachIndexed { index, picsCallback ->
                        Timber.d(
                            "onPicsProduct: ${index + 1} of ${callback.results.size}" +
                                    "\n\tReceived PICS result of ${picsCallback.apps.size} app(s)." +
                                    "\n\tReceived PICS result of ${picsCallback.packages.size} package(s).",
                        )

                        val steamApps = picsCallback.apps.values.mapNotNull { app ->
                            val appFromDb = appDao.findApp(app.id)
                            val packageId = appFromDb?.packageId ?: INVALID_PKG_ID
                            val packageFromDb = if (packageId != INVALID_PKG_ID) licenseDao.findLicense(packageId) else null
                            val ownerAccountId = packageFromDb?.ownerAccountId ?: emptyList()

                            // Apps with -1 for the ownerAccountId should be added.
                            //  This can help with friend game names.

                            // TODO maybe apps with -1 for the ownerAccountId can be stripped with necessities and name.

                            if (app.changeNumber != appFromDb?.lastChangeNumber) {
                                app.keyValues.generateSteamApp().copy(
                                    packageId = packageId,
                                    ownerAccountId = ownerAccountId,
                                    receivedPICS = true,
                                    lastChangeNumber = app.changeNumber,
                                    licenseFlags = packageFromDb?.licenseFlags ?: EnumSet.noneOf(ELicenseFlags::class.java),
                                )
                            } else {
                                null
                            }
                        }

                        if (steamApps.isNotEmpty()) {
                            Timber.i("Inserting ${steamApps.size} PICS apps to database")
                            db.withTransaction {
                                appDao.insertAll(steamApps)
                            }
                        }
                    }
                }
        }

        scope.launch {
            packagePicsChannel.receiveAsFlow()
                .filter { it.isNotEmpty() }
                .buffer(capacity = MAX_PICS_BUFFER, onBufferOverflow = BufferOverflow.SUSPEND)
                .collect { packageRequests ->
                    Timber.d("Processing ${packageRequests.size} package PICS requests")

                    val callback = _steamApps!!.picsGetProductInfo(
                        apps = emptyList(),
                        packages = packageRequests,
                    ).await()

                    callback.results.forEach { picsCallback ->
                        // Don't race the queue.
                        val queue = Collections.synchronizedList(mutableListOf<Int>())

                        db.withTransaction {
                            picsCallback.packages.values.forEach { pkg ->
                                val appIds = pkg.keyValues["appids"].children.map { it.asInteger() }
                                licenseDao.updateApps(pkg.id, appIds)

                                val depotIds = pkg.keyValues["depotids"].children.map { it.asInteger() }
                                licenseDao.updateDepots(pkg.id, depotIds)

                                // Insert a stub row (or update) of SteamApps to the database.
                                appIds.forEach { appid ->
                                    val steamApp = appDao.findApp(appid)?.copy(packageId = pkg.id)
                                    if (steamApp != null) {
                                        appDao.update(steamApp)
                                    } else {
                                        val stubSteamApp = SteamApp(id = appid, packageId = pkg.id)
                                        appDao.insert(stubSteamApp)
                                    }
                                }

                                queue.addAll(appIds)
                            }
                        }

                        // TODO: This could be an issue. (Stalling)
                        _steamApps!!.picsGetAccessTokens(
                            appIds = queue,
                            packageIds = emptyList(),
                        ).await()
                            .appTokens
                            .forEach { (key, value) ->
                                appTokens[key] = value
                            }

                        // Get PICS information with the app ids.
                        queue
                            .map { PICSRequest(id = it, accessToken = appTokens[it] ?: 0L) }
                            .chunked(MAX_PICS_BUFFER)
                            .forEach { chunk ->
                                Timber.d("bufferedPICSGetProductInfo: Queueing ${chunk.size} for PICS")
                                appPicsChannel.send(chunk)
                            }
                    }
                }
        }
    }
}
