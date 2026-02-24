package app.gamenative.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.widget.Toast
import androidx.room.withTransaction
import app.gamenative.BuildConfig
import app.gamenative.R
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.data.DepotInfo
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GameProcessInfo
import app.gamenative.data.LaunchInfo
import app.gamenative.data.OwnedGames
import app.gamenative.data.PostSyncInfo
import app.gamenative.data.SteamApp
import app.gamenative.data.SteamControllerConfigDetail
import app.gamenative.data.SteamFriend
import app.gamenative.data.SteamLicense
import app.gamenative.data.UserFileInfo
import app.gamenative.data.EncryptedAppTicket
import app.gamenative.db.PluviaDatabase
import app.gamenative.db.dao.ChangeNumbersDao
import app.gamenative.db.dao.FileChangeListsDao
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.db.dao.SteamLicenseDao
import app.gamenative.db.dao.CachedLicenseDao
import app.gamenative.enums.LoginResult
import app.gamenative.enums.OS
import app.gamenative.enums.OSArch
import app.gamenative.enums.SaveLocation
import app.gamenative.enums.SyncResult
import app.gamenative.events.AndroidEvent
import app.gamenative.events.SteamEvent
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.Net
import app.gamenative.utils.SteamUtils
import app.gamenative.utils.MarkerUtils
import app.gamenative.enums.Marker
import app.gamenative.utils.generateSteamApp
import com.winlator.xenvironment.ImageFs
import dagger.hilt.android.AndroidEntryPoint
import `in`.dragonbra.javasteam.enums.EDepotFileFlag
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
import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import `in`.dragonbra.javasteam.depotdownloader.IDownloadListener
import `in`.dragonbra.javasteam.depotdownloader.Steam3Session
import `in`.dragonbra.javasteam.steam.discovery.FileServerListProvider
import `in`.dragonbra.javasteam.steam.discovery.ServerQuality
import `in`.dragonbra.javasteam.steam.handlers.steamapps.GamePlayedInfo
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.SteamFriends
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.PersonaStateCallback
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
import `in`.dragonbra.javasteam.types.KeyValue
import `in`.dragonbra.javasteam.types.PublishedFileID
import `in`.dragonbra.javasteam.types.SteamID
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
import java.util.concurrent.atomic.AtomicBoolean
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.lang.NullPointerException
import app.gamenative.data.AppInfo
import app.gamenative.db.dao.AppInfoDao
import kotlinx.coroutines.ensureActive
import app.gamenative.utils.LicenseSerializer
import app.gamenative.data.CachedLicense
import com.winlator.container.Container
import `in`.dragonbra.javasteam.depotdownloader.data.AppItem
import `in`.dragonbra.javasteam.depotdownloader.data.DownloadItem
import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.PlayingSessionStateCallback
import `in`.dragonbra.javasteam.steam.steamclient.AsyncJobFailedException
import `in`.dragonbra.javasteam.types.DepotManifest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import org.json.JSONObject
import android.util.Base64
import app.gamenative.data.DownloadingAppInfo
import app.gamenative.db.dao.DownloadingAppInfoDao
import app.gamenative.db.dao.EncryptedAppTicketDao
import com.winlator.container.ContainerManager
import kotlinx.coroutines.flow.update
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class SteamService : Service(), IChallengeUrlChanged {

    // To view log messages in android logcat properly
    private val logger = object : LogListener {
        override fun onLog(clazz: Class<*>, message: String?, throwable: Throwable?) {
            val logMessage = message ?: "No message given"
            Timber.i(throwable, "[${clazz.simpleName}] -> $logMessage")
        }

        override fun onError(clazz: Class<*>, message: String?, throwable: Throwable?) {
            val logMessage = message ?: "No message given"
            Timber.e(throwable, "[${clazz.simpleName}] -> $logMessage")
        }
    }

    @Inject
    lateinit var db: PluviaDatabase

    @Inject
    lateinit var licenseDao: SteamLicenseDao

    @Inject
    lateinit var appDao: SteamAppDao

    @Inject
    lateinit var changeNumbersDao: ChangeNumbersDao

    @Inject
    lateinit var appInfoDao: AppInfoDao

    @Inject
    lateinit var fileChangeListsDao: FileChangeListsDao

    @Inject
    lateinit var cachedLicenseDao: CachedLicenseDao

    @Inject
    lateinit var encryptedAppTicketDao: EncryptedAppTicketDao

    @Inject
    lateinit var downloadingAppInfoDao: DownloadingAppInfoDao

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

    private var licenses: List<License> = emptyList()

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

    @Volatile
    private var isWifiConnected: Boolean = true

    // Add these as class properties
    private var picsGetProductInfoJob: Job? = null
    private var picsChangesCheckerJob: Job? = null
    private var friendCheckerJob: Job? = null

    private val _isPlayingBlocked = MutableStateFlow(false)
    val isPlayingBlocked = _isPlayingBlocked.asStateFlow()

    // Cache in-memory the local persona state.
    private val _localPersona = MutableStateFlow(
        SteamFriend(name = PrefManager.steamUserName, avatarHash = PrefManager.steamUserAvatarHash),
    )
    val localPersona = _localPersona.asStateFlow()

    companion object {
        const val MAX_PICS_BUFFER = 256

        const val MAX_RETRY_ATTEMPTS = 20

        const val INVALID_APP_ID: Int = Int.MAX_VALUE
        const val INVALID_PKG_ID: Int = Int.MAX_VALUE
        private const val STEAM_CONTROLLER_CONFIG_FILENAME = "steam_controller_config.vdf"

        /**
         * Default timeout to use when making requests
         */
        var requestTimeout = 30.seconds

        /**
         * Default timeout to use when reading the response body
         */
        var responseTimeout = 120.seconds

        private val PROTOCOL_TYPES = EnumSet.of(ProtocolTypes.WEB_SOCKET)

        internal var instance: SteamService? = null

        private val downloadJobs = ConcurrentHashMap<Int, DownloadInfo>()

        private fun notifyDownloadStarted(appId: Int) {
            PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(appId, true))
        }

        private fun notifyDownloadStopped(appId: Int) {
            PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(appId, false))
        }

        private fun removeDownloadJob(appId: Int) {
            val removed = downloadJobs.remove(appId)
            if (removed != null) {
                notifyDownloadStopped(appId)
            }
        }

        /** Returns true if there is an incomplete download on disk (no complete marker). */
        fun hasPartialDownload(appId: Int): Boolean {
            val downloadingApp = getDownloadingAppInfoOf(appId)
            if (downloadingApp != null) {
                return true
            }

            val dirPath = getAppDirPath(appId)
            return File(dirPath).exists() && !MarkerUtils.hasMarker(dirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
        }

        private val syncInProgressApps = ConcurrentHashMap<Int, AtomicBoolean>()

        private fun getSyncFlag(appId: Int): AtomicBoolean {
            val existing = syncInProgressApps[appId]
            if (existing != null) {
                return existing
            }
            val created = AtomicBoolean(false)
            val prior = syncInProgressApps.putIfAbsent(appId, created)
            return prior ?: created
        }

        private fun tryAcquireSync(appId: Int): Boolean {
            val flag = getSyncFlag(appId)
            return flag.compareAndSet(false, true)
        }

        private fun releaseSync(appId: Int) {
            val flag = syncInProgressApps[appId]
            flag?.set(false)
            if (flag != null && !flag.get()) {
                syncInProgressApps.remove(appId, flag)
            }
        }

        // Track whether a game is currently running to prevent premature service stop
        @JvmStatic
        @Volatile
        var keepAlive: Boolean = false

        @Volatile
        var isImporting: Boolean = false

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
            get() = Paths.get(DownloadService.baseCacheDirPath, "server_list.bin").pathString

        val internalAppInstallPath: String
            get() = Paths.get(DownloadService.baseDataDirPath, "Steam", "steamapps", "common").pathString

        val externalAppInstallPath: String
            get() = Paths.get(PrefManager.externalStoragePath, "Steam", "steamapps", "common").pathString

        private val internalAppStagingPath: String
            get() {
                return Paths.get(DownloadService.baseDataDirPath, "Steam", "steamapps", "staging").pathString
            }
        private val externalAppStagingPath: String
            get() {
                return Paths.get(PrefManager.externalStoragePath, "Steam", "steamapps", "staging").pathString
            }

        val defaultStoragePath: String
            get() {
                return if (PrefManager.useExternalStorage && File(PrefManager.externalStoragePath).exists()) {
                    // We still have an SD card file structure as expected
                    Timber.i("External storage path is " + PrefManager.externalStoragePath)
                    PrefManager.externalStoragePath
                } else {
                    if (instance != null) {
                        return DownloadService.baseDataDirPath
                    }
                    return ""
                }
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
            get() = instance?.familyGroupMembers ?: emptyList()

        val isLoginInProgress: Boolean
            get() = instance?._loginResult == LoginResult.InProgress

        suspend fun setPersonaState(state: EPersonaState) = withContext(Dispatchers.IO) {
            PrefManager.personaState = state
            instance?._steamFriends?.setPersonaState(state)
        }

        suspend fun requestUserPersona() = withContext(Dispatchers.IO) {
            // in order to get user avatar url and other info
            userSteamId?.let { instance?._steamFriends?.requestFriendInfo(it) }
        }

        suspend fun getSelfCurrentlyPlayingAppId(): Int? = withContext(Dispatchers.IO) {
            val self = instance?.localPersona?.value ?: return@withContext null
            if (self.isPlayingGame) self.gameAppID else null
        }

        suspend fun kickPlayingSession(onlyGame: Boolean = true): Boolean = withContext(Dispatchers.IO) {
            val user = instance?._steamUser ?: return@withContext false
            try {
                instance?._isPlayingBlocked?.value = true
                user.kickPlayingSession(onlyStopGame = onlyGame)

                // Wait for PlayingSessionStateCallback to indicate unblocked
                val deadline = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < deadline) {
                    if (instance?._isPlayingBlocked?.value == false) return@withContext true
                    delay(100)
                }
                false
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Get licenses from database for use with DepotDownloader
         */
        suspend fun getLicensesFromDb(): List<License> = withContext(Dispatchers.IO) {
            val cached = instance?.cachedLicenseDao?.getAll() ?: return@withContext emptyList()
            cached.mapNotNull { cachedLicense ->
                LicenseSerializer.deserializeLicense(cachedLicense.licenseJson)
            }
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

        fun getDownloadingAppInfoOf(appId: Int): DownloadingAppInfo? {
            return runBlocking(Dispatchers.IO) { instance?.downloadingAppInfoDao?.getDownloadingApp(appId) }
        }

        fun getDownloadableDlcAppsOf(appId: Int): List<SteamApp>? {
            return runBlocking(Dispatchers.IO) { instance?.appDao?.findDownloadableDLCApps(appId) }
        }

        fun getHiddenDlcAppsOf(appId: Int): List<SteamApp>? {
            return runBlocking(Dispatchers.IO) { instance?.appDao?.findHiddenDLCApps(appId) }
        }

        fun getInstalledApp(appId: Int): AppInfo? {
            return runBlocking(Dispatchers.IO) { instance?.appInfoDao?.getInstalledApp(appId) }
        }

        fun getInstalledDepotsOf(appId: Int): List<Int>? {
            return getInstalledApp(appId)?.downloadedDepots
        }

        fun getInstalledDlcDepotsOf(appId: Int): List<Int>? {
            return getInstalledApp(appId)?.dlcDepots
        }

        fun getAppDownloadInfo(appId: Int): DownloadInfo? {
            return downloadJobs[appId]
        }

        fun isAppInstalled(appId: Int): Boolean {
            return MarkerUtils.hasMarker(getAppDirPath(appId), Marker.DOWNLOAD_COMPLETE_MARKER)
        }

        fun getAppDlc(appId: Int): Map<Int, DepotInfo> {
            return getAppInfoOf(appId)?.let {
                it.depots.filter { it.value.dlcAppId != INVALID_APP_ID }
            }.orEmpty()
        }

        suspend fun getOwnedAppDlc(appId: Int): Map<Int, DepotInfo> {
            val client = instance?.steamClient ?: return emptyMap()
            val accountId = client.steamID?.accountID?.toInt() ?: return emptyMap()
            val ownedGameIds = getOwnedGames(userSteamId!!.convertToUInt64()).map { it.appId }.toHashSet()


            return getAppDlc(appId).filter { (_, depot) ->
                when {
                    /* Base-game depots always download */
                    depot.dlcAppId == INVALID_APP_ID -> true

                    /* ① licence cache */
                    instance?.licenseDao?.findLicense(depot.dlcAppId) != null -> true

                    /* ② PICS row */
                    instance?.appDao?.findApp(depot.dlcAppId) != null -> true

                    /* ③ owned-games list */
                    depot.dlcAppId in ownedGameIds -> true

                    /* ④ final online / cached call */
                    else -> false
                }
            }.toMap()
        }

        fun getMainAppDlcIdsWithoutProperDepotDlcIds(appId: Int): MutableList<Int> {
            val mainAppDlcIds = mutableListOf<Int>()
            val hiddenDlcAppIds = getHiddenDlcAppsOf(appId).orEmpty().map { it.id }

            val appInfo = getAppInfoOf(appId)
            if (appInfo != null) {
                // for each of the dlcAppId found in main depots, filter the count = 1, add that dlcAppId to dlcAppIds
                val checkingAppDlcIds = appInfo.depots.filter { it.value.dlcAppId != INVALID_APP_ID }.map { it.value.dlcAppId }.distinct()
                checkingAppDlcIds.forEach { checkingDlcId ->
                    val checkMap = appInfo.depots.filter { it.value.dlcAppId == checkingDlcId }
                    if (checkMap.size == 1) {
                        val depotInfo = checkMap[checkMap.keys.first()]!!
                        if (depotInfo.osList.contains(OS.none) &&
                            depotInfo.manifests.isEmpty() &&
                            hiddenDlcAppIds.isNotEmpty() && hiddenDlcAppIds.contains(checkingDlcId)) {
                            mainAppDlcIds.add(checkingDlcId)
                        }
                    }
                }
            }

            return mainAppDlcIds
        }

        /**
         * Refresh the owned games list by querying Steam, diffing with the local DB, and
         * queueing PICS requests for anything new so metadata gets populated.
         *
         * @return number of newly discovered appIds that were scheduled for PICS.
         */
        suspend fun refreshOwnedGamesFromServer(): Int = withContext(Dispatchers.IO) {
            val service = instance ?: return@withContext 0
            val unifiedFriends = service._unifiedFriends ?: return@withContext 0
            val steamId = userSteamId ?: return@withContext 0

            runCatching {
                val ownedGames = unifiedFriends.getOwnedGames(steamId.convertToUInt64())
                val remoteAppIds = ownedGames.map { it.appId }.filter { it > 0 }.toSet()
                if (remoteAppIds.isEmpty()) {
                    return@runCatching 0
                }

                val localAppIds = service.appDao.getAllAppIds().toSet()
                val missingAppIds = remoteAppIds - localAppIds
                if (missingAppIds.isEmpty()) {
                    return@runCatching 0
                }

                missingAppIds
                    .chunked(MAX_PICS_BUFFER)
                    .forEach { chunk ->
                        val requests = chunk.map { PICSRequest(id = it) }
                        service.appPicsChannel.send(requests)
                    }

                missingAppIds.size
            }.onFailure { error ->
                Timber.tag("SteamService").e(error, "Failed to refresh owned games from server")
            }.getOrDefault(0)
        }

        /**
         * Common Filter for downloadable depots
         */
        fun filterForDownloadableDepots(depot: DepotInfo, has64Bit: Boolean, preferredLanguage: String, ownedDlc: Map<Int, DepotInfo>?): Boolean {
            if (depot.manifests.isEmpty() && depot.encryptedManifests.isNotEmpty())
                return false
            // 1. Has something to download
            if (depot.manifests.isEmpty() && !depot.sharedInstall)
                return false
            // 2. Supported OS
            if (!(depot.osList.contains(OS.windows) ||
                        (!depot.osList.contains(OS.linux) && !depot.osList.contains(OS.macos)))
            )
                return false
            // 3. 64-bit or indeterminate
            // Arch selection: allow 64-bit and Unknown always.
            // Allow 32-bit only when no 64-bit depot exists.
            val archOk = when (depot.osArch) {
                OSArch.Arch64, OSArch.Unknown -> true
                OSArch.Arch32 -> !has64Bit
                else -> false
            }
            if (!archOk) return false
            // 4. DLC you actually own
            if (depot.dlcAppId != INVALID_APP_ID && ownedDlc != null && !ownedDlc.containsKey(depot.depotId))
                return false
            // 5. Language filter - if depot has language, it must match preferred language
            if (depot.language.isNotEmpty() && depot.language != preferredLanguage)
                return false

            return true
        }

        fun getMainAppDepots(appId: Int, containerLanguage: String): Map<Int, DepotInfo> {
            val appInfo = getAppInfoOf(appId) ?: return emptyMap()
            val ownedDlc = runBlocking { getOwnedAppDlc(appId) }

            // If the game ships any 64-bit depot, prefer those and ignore x86 ones
            val has64Bit = appInfo.depots.values.any { it.osArch == OSArch.Arch64 }

            return appInfo.depots.asSequence()
                .filter { (depotId, depot) ->
                    return@filter filterForDownloadableDepots(depot, has64Bit, containerLanguage, ownedDlc)
                }
                .associate { it.toPair() }
        }

        /**
         * Get downloadable depots for a given app (default language), including all DLCs
         * @return Map of app ID to depot ID to depot info
         */
        fun getDownloadableDepots(appId: Int): Map<Int, DepotInfo> {
            val preferredLanguage = PrefManager.containerLanguage
            return getDownloadableDepots(appId, preferredLanguage)
        }

        /**
         * Get downloadable depots for a given app (container language), including all DLCs
         * @return Map of app ID to depot ID to depot info
         */
        fun getDownloadableDepots(appId: Int, preferredLanguage: String): Map<Int, DepotInfo> {
            val appInfo = getAppInfoOf(appId) ?: return emptyMap()
            val ownedDlc = runBlocking { getOwnedAppDlc(appId) }

            // If the game ships any 64-bit depot, prefer those and ignore x86 ones
            val has64Bit = appInfo.depots.values.any { it.osArch == OSArch.Arch64 }

            val map = appInfo.depots
                .asSequence()
                .filter { (depotId, depot) ->
                    return@filter filterForDownloadableDepots(depot, has64Bit, preferredLanguage, ownedDlc)
                }
                .associate { it.toPair() }
                .toMutableMap()

            val indirectDlcApps = getDownloadableDlcAppsOf(appId).orEmpty()
            indirectDlcApps.forEach { dlcApp ->
                dlcApp.depots
                    .asSequence()
                    .filter { (depotId, depot) ->
                        return@filter filterForDownloadableDepots(depot, has64Bit, preferredLanguage, null)
                    }
                    .associate { it.toPair() }
                    .forEach { (depotId, depot) ->
                        // Add DLC Depots with custom object
                        map[depotId] = DepotInfo(
                            depotId = depot.depotId,
                            dlcAppId = dlcApp.id, // Set to DLC App ID
                            optionalDlcId = depot.optionalDlcId,
                            depotFromApp = depot.depotFromApp,
                            sharedInstall = depot.sharedInstall,
                            osList = depot.osList,
                            osArch = depot.osArch,
                            language = depot.language,
                            manifests = depot.manifests,
                            encryptedManifests = depot.encryptedManifests,
                        )
                    }
            }

            return map
        }

        fun getAppDirName(app: SteamApp?): String {
            // The folder name, if it got made
            var appName = app?.config?.installDir.orEmpty()
            if (appName.isEmpty()) {
                appName = app?.name.orEmpty()
            }
            return appName
        }

        fun getAppDirPath(gameId: Int): String {

            val info = getAppInfoOf(gameId)
            val appName = getAppDirName(info)
            val oldName = info?.name.orEmpty()

            // Internal first (legacy installs), external second
            val internalPath = Paths.get(internalAppInstallPath, appName)
            if (Files.exists(internalPath)) return internalPath.pathString
            val internalOld = Paths.get(internalAppInstallPath, oldName)
            if (oldName.isNotEmpty() && Files.exists(internalOld)) return internalOld.pathString

            val externalPath = Paths.get(externalAppInstallPath, appName)
            if (Files.exists(externalPath)) return externalPath.pathString
            val externalOld = Paths.get(externalAppInstallPath, oldName)
            if (oldName.isNotEmpty() && Files.exists(externalOld)) return externalOld.pathString

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
            is Int -> (flags and 0x20) != 0 || (flags and 0x80) != 0
            is Long -> ((flags and 0x20L) != 0L) || ((flags and 0x80L) != 0L)

            else -> false
        }

        /* -------------------------------------------------------------------------- */
        /* 1. Extra patterns & word lists                                             */
        /* -------------------------------------------------------------------------- */

        // Unreal Engine "Shipping" binaries (e.g. Stray-Win64-Shipping.exe)
        private val UE_SHIPPING = Regex(
            """.*-win(32|64)(-shipping)?\.exe$""",
            RegexOption.IGNORE_CASE,
        )

        // UE folder hint …/Binaries/Win32|64/…
        private val UE_BINARIES = Regex(
            """.*/binaries/win(32|64)/.*\.exe$""",
            RegexOption.IGNORE_CASE,
        )

        // Tools / crash-dumpers to push down
        private val NEGATIVE_KEYWORDS = listOf(
            "crash", "handler", "viewer", "compiler", "tool",
            "setup", "unins", "eac", "launcher", "steam",
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
            hasExeFlag: Boolean,
        ): Int {
            var s = 0
            val path = file.fileName.lowercase()

            // 1️⃣ UE shipping or binaries folder bonus
            if (UE_SHIPPING.matches(path)) s += 300
            if (UE_BINARIES.containsMatchIn(path)) s += 250

            // 2️⃣ root-folder exe bonus
            if (!path.contains('/')) s += 200

            // 3️⃣ filename contains the game / installDir
            if (path.contains(gameName) || fuzzyMatch(path, gameName)) s += 100

            // 4️⃣ obvious tool / crash-dumper penalty
            if (NEGATIVE_KEYWORDS.any { it in path }) s -= 150
            if (GENERIC_NAME.matches(file.fileName)) s -= 200   // ← new

            // 5️⃣ Executable | CustomExecutable flag
            if (hasExeFlag) s += 50

            Timber.i("Score for $path: $s")

            return s
        }

        fun FileData.isStub(): Boolean {
            /* stub detector (same short rules) */
            val generic = Regex("^[a-z]\\d{1,3}\\.exe$", RegexOption.IGNORE_CASE)
            val bad = listOf("launcher", "steam", "crash", "handler", "setup", "unins", "eac")
            val n = fileName.lowercase()
            val stub = generic.matches(n) || bad.any { it in n } || totalSize < 1_000_000
            if (stub) Timber.d("Stub filtered: $fileName  size=$totalSize")
            return stub
        }

        /** select the primary binary */
        fun choosePrimaryExe(
            files: List<FileData>?,
            gameName: String,
        ): FileData? = files?.maxWithOrNull { a, b ->
            val sa = scoreExe(a, gameName, isExecutable(a.flags))   // <- fixed
            val sb = scoreExe(b, gameName, isExecutable(b.flags))

            when {
                sa != sb -> sa - sb                                 // higher score wins
                else -> (a.totalSize - b.totalSize).toInt()     // tie-break on size
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

            /* ---------------------------------------------------------- */
            val flagged = mutableListOf<Pair<FileData, Long>>()   // (file, depotSize)
            var largestDepotSize = 0L

            // Use DepotDownloader to fetch manifests
            val steamClient = instance?.steamClient
            val licenses = runBlocking { getLicensesFromDb() }
            if (steamClient == null || licenses.isEmpty()) {
                Timber.w("Cannot fetch manifests: steamClient or licenses not available")
                // Fallback to last resort
                return (getAppInfoOf(appId)?.let { appInfo ->
                    getWindowsLaunchInfos(appId).firstOrNull()
                })?.executable ?: ""
            }

            for (depot in depots) {
                val mi = depot.manifests["public"] ?: continue
                if (mi.size > largestDepotSize) largestDepotSize = mi.size

                // Check cache first
                val man = DepotManifest.loadFromFile("${getAppDirPath(appId)}/.DepotDownloader/${depot.depotId}_${mi.gid}.manifest")

                Timber.d("Using manifest for depot ${depot.depotId}  size=${mi.size}")

                /* 1️⃣ exact launch entry that isn't a stub */
                man?.files?.firstOrNull { f ->
                    f.fileName.lowercase() in launchTargets && !f.isStub()
                }?.let {
                    Timber.i("Picked via launch entry: ${it.fileName}")
                    return it.fileName.replace('\\', '/').toString()
                }

                /* collect for later */
                man?.files?.filter { isExecutable(it.flags) || it.fileName.endsWith(".exe", true) }
                    ?.forEach { flagged += it to mi.size }
            }

            Timber.i("Flagged executable candidates: ${flagged.map { it.first.fileName }}")

            /* 2️⃣ scorer (unchanged) */
            choosePrimaryExe(
                flagged
                    .map { it.first }
                    .let { pool ->
                        val noStubs = pool.filterNot { it.isStub() }
                        if (noStubs.isNotEmpty()) noStubs else pool
                    },
                installDir.lowercase(),
            )?.let {
                Timber.i("Picked via scorer: ${it.fileName}")
                return it.fileName.replace('\\', '/')
            }

            /* 3️⃣ fallback: biggest exe from the biggest depot */
            flagged
                .filter { it.second == largestDepotSize }
                .maxByOrNull { it.first.totalSize }
                ?.let {
                    Timber.i("Picked via largest-depot fallback: ${it.first.fileName}")
                    return it.first.fileName.replace('\\', '/').toString()
                }

            /* 4️⃣ last resort */
            Timber.w("No executable found; falling back to install dir")
            return (getAppInfoOf(appId)?.let { appInfo ->
                getWindowsLaunchInfos(appId).firstOrNull()
            })?.executable ?: ""
        }

        /**
         * Resolves the effective launch executable for a Steam game (container config or auto-detected).
         * Returns a non-empty sentinel when [Container.isLaunchRealSteam] is true so the launch is not blocked.
         */
        fun getLaunchExecutable(appId: String, container: Container): String {
            if (container.isLaunchRealSteam) return "steam"
            val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
            return container.executablePath.ifEmpty { getInstalledExe(gameId) }
        }

        fun deleteApp(appId: Int): Boolean {
            // Remove any download-complete marker
            MarkerUtils.removeMarker(getAppDirPath(appId), Marker.DOWNLOAD_COMPLETE_MARKER)
            // Remove from DB
            with(instance!!) {
                scope.launch {
                    db.withTransaction {
                        appInfoDao.deleteApp(appId)
                        changeNumbersDao.deleteByAppId(appId)
                        fileChangeListsDao.deleteByAppId(appId)
                        downloadingAppInfoDao.deleteApp(appId)

                        val indirectDlcAppIds = getDownloadableDlcAppsOf(appId).orEmpty().map { it.id }
                        indirectDlcAppIds.forEach { dlcAppId ->
                            appInfoDao.deleteApp(dlcAppId)
                            changeNumbersDao.deleteByAppId(dlcAppId)
                            fileChangeListsDao.deleteByAppId(dlcAppId)
                        }
                    }
                }
            }

            val appDirPath = getAppDirPath(appId)

            return File(appDirPath).deleteRecursively()
        }

        fun downloadApp(appId: Int): DownloadInfo? {
            val currentDownloadInfo = downloadJobs[appId]
            if (currentDownloadInfo != null) {
                return downloadApp(appId, currentDownloadInfo.downloadingAppIds, isUpdateOrVerify = false)
            } else {
                // If downloading app info exists
                val downloadingAppInfo = getDownloadingAppInfoOf(appId)
                if (downloadingAppInfo != null) {
                    return downloadApp(appId, downloadingAppInfo.dlcAppIds.orEmpty(), isUpdateOrVerify = false)
                } else {
                    // Otherwise it is verifying files
                    val dlcAppIds = getInstalledDlcDepotsOf(appId).orEmpty().toMutableList()

                    getDownloadableDlcAppsOf(appId)?.forEach { dlcApp ->
                        val installedDlcApp = getInstalledApp(dlcApp.id)
                        if (installedDlcApp != null) {
                            dlcAppIds.add(installedDlcApp.id)
                        }
                    }

                    return downloadApp(appId, dlcAppIds, isUpdateOrVerify = true)
                }
            }
        }

        fun downloadApp(appId: Int, dlcAppIds: List<Int>, isUpdateOrVerify: Boolean): DownloadInfo? {
            // Enforce Wi-Fi-only downloads
            if (PrefManager.downloadOnWifiOnly && instance?.isWifiConnected == false) {
                instance?.notificationHelper?.notify("Not connected to Wi‑Fi/LAN")
                return null
            }
            return getAppInfoOf(appId)?.let { appInfo ->
                val container = ContainerManager(instance!!.applicationContext).getContainerById("STEAM_${appId}")
                val containerLanguage = if (container != null) {
                    container.language
                } else {
                    PrefManager.containerLanguage
                }

                Timber.tag("SteamService").d("downloadApp: downloading app $appId with language $containerLanguage")

                val depots = getDownloadableDepots(appId = appId, preferredLanguage = containerLanguage)
                downloadApp(
                    appId = appId,
                    downloadableDepots = depots,
                    userSelectedDlcAppIds = dlcAppIds,
                    branch = "public",
                    containerLanguage = containerLanguage,
                    isUpdateOrVerify = isUpdateOrVerify)
            }
        }

        fun isImageFsInstalled(context: Context): Boolean {
            return ImageFs.find(context).rootDir.exists()
        }

        fun isImageFsInstallable(context: Context, variant: String): Boolean {
            val imageFs = ImageFs.find(context)
            if (variant.equals(Container.BIONIC)) {
                return File(imageFs.filesDir, "imagefs_bionic.txz").exists() || context.assets.list("")
                    ?.contains("imagefs_bionic.txz") == true
            } else {
                return File(imageFs.filesDir, "imagefs_gamenative.txz").exists() || context.assets.list("")
                    ?.contains("imagefs_gamenative.txz") == true
            }
        }

        fun isSteamInstallable(context: Context): Boolean {
            val imageFs = ImageFs.find(context)
            return File(imageFs.filesDir, "steam.tzst").exists()
        }

        fun isFileInstallable(context: Context, filename: String): Boolean {
            val imageFs = ImageFs.find(context)
            return File(imageFs.filesDir, filename).exists()
        }

        suspend fun fetchFile(
            url: String,
            dest: File,
            onProgress: (Float) -> Unit,
        ) = withContext(Dispatchers.IO) {
            val tmp = File(dest.absolutePath + ".part")
            try {
                val http = SteamUtils.http

                val req = Request.Builder().url(url).build()
                http.newCall(req).execute().use { rsp ->
                    check(rsp.isSuccessful) { "HTTP ${rsp.code}" }
                    val body = rsp.body ?: error("empty body")
                    val total = body.contentLength()
                    tmp.outputStream().use { out ->
                        body.byteStream().copyTo(out, 8 * 1024) { read ->
                            onProgress(read.toFloat() / total)
                        }
                    }
                    if (total > 0 && tmp.length() != total) {
                        tmp.delete()
                        error("incomplete download")
                    }
                    if (!tmp.renameTo(dest)) {
                        tmp.copyTo(dest, overwrite = true)
                        tmp.delete()
                    }
                }
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
        }

        suspend fun fetchFileWithFallback(
            fileName: String,
            dest: File,
            context: Context,
            onProgress: (Float) -> Unit,
        ) = withContext(Dispatchers.IO) {
            val primaryUrl = "https://downloads.gamenative.app/$fileName"
            val fallbackUrl = "https://pub-9fcd5294bd0d4b85a9d73615bf98f3b5.r2.dev/$fileName"
            try {
                fetchFile(primaryUrl, dest, onProgress)
            } catch (e: Exception) {
                Timber.w(e, "Primary download failed; retrying with fallback URL")
                try {
                    fetchFile(fallbackUrl, dest, onProgress)
                } catch (e2: Exception) {
                    withContext(Dispatchers.Main) {
                        val msg = "Download failed with ${e2.message ?: e2.toString()}. Please disable VPN or try a different network."
                        android.widget.Toast.makeText(context.applicationContext, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        /** copyTo with progress callback */
        private inline fun InputStream.copyTo(
            out: OutputStream,
            bufferSize: Int = DEFAULT_BUFFER_SIZE,
            progress: (Long) -> Unit,
        ) {
            val buf = ByteArray(bufferSize)
            var bytesRead: Int
            var total = 0L
            while (read(buf).also { bytesRead = it } >= 0) {
                if (bytesRead == 0) continue
                out.write(buf, 0, bytesRead)
                total += bytesRead
                progress(total)
            }
        }

        fun downloadImageFs(
            onDownloadProgress: (Float) -> Unit,
            parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
            variant: String,
            context: Context,
        ) = parentScope.async {
            Timber.i("imagefs will be downloaded")
            if (variant == Container.BIONIC) {
                val dest = File(instance!!.filesDir, "imagefs_bionic.txz")
                Timber.d("Downloading imagefs_bionic to " + dest.toString());
                fetchFileWithFallback("imagefs_bionic.txz", dest, context, onDownloadProgress)
            } else {
                Timber.d("Downloading imagefs_gamenative to " + File(instance!!.filesDir, "imagefs_gamenative.txz"));
                fetchFileWithFallback(
                    "imagefs_gamenative.txz",
                    File(instance!!.filesDir, "imagefs_gamenative.txz"),
                    context,
                    onDownloadProgress,
                )
            }
        }

        fun downloadImageFsPatches(
            onDownloadProgress: (Float) -> Unit,
            parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
            context: Context,
        ) = parentScope.async {
            Timber.i("imagefs will be downloaded")
            val dest = File(instance!!.filesDir, "imagefs_patches_gamenative.tzst")
            Timber.d("Downloading imagefs_patches_gamenative.tzst to " + dest.toString());
            fetchFileWithFallback("imagefs_patches_gamenative.tzst", dest, context, onDownloadProgress)
        }

        fun downloadFile(
            onDownloadProgress: (Float) -> Unit,
            parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
            context: Context,
            fileName: String
        ) = parentScope.async {
            Timber.i("${fileName} will be downloaded")
            val dest = File(instance!!.filesDir, fileName)
            Timber.d("Downloading ${fileName} to " + dest.toString());
            fetchFileWithFallback(fileName, dest, context, onDownloadProgress)
        }

        fun downloadSteam(
            onDownloadProgress: (Float) -> Unit,
            parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
            context: Context,
        ) = parentScope.async {
            Timber.i("imagefs will be downloaded")
            val dest = File(instance!!.filesDir, "steam.tzst")
            Timber.d("Downloading steam.tzst to " + dest.toString());
            fetchFileWithFallback("steam.tzst", dest, context, onDownloadProgress)
        }

        private fun selectSteamControllerConfig(
            details: List<SteamControllerConfigDetail>,
        ): SteamControllerConfigDetail? {
            if (details.isEmpty()) return null

            val branchPriority = listOf("default", "public")
            val controllerPriority = listOf(
                "controller_xbox360",
                "controller_xboxone",
                "controller_steamcontroller_gordon",
            )

            for (branch in branchPriority) {
                for (controllerType in controllerPriority) {
                    val match = details.firstOrNull { detail ->
                        detail.controllerType.equals(controllerType, ignoreCase = true) &&
                            detail.enabledBranches.any { it.equals(branch, ignoreCase = true) }
                    }
                    if (match != null) return match
                }
            }

            return null
        }

        private fun resolveSteamInputManifestFile(
            appId: Int,
            appDirPath: String,
        ): File? {
            val manifestPath = getAppInfoOf(appId)
                ?.config
                ?.steamInputManifestPath
                ?.trim()
                .orEmpty()
            if (manifestPath.isEmpty()) return null

            return resolvePathCaseInsensitive(appDirPath, manifestPath)
        }

        private fun loadConfigFromManifest(
            manifestFile: File,
        ): String? {
            if (!manifestFile.exists()) return null
            val manifestDirPath = manifestFile.parentFile?.path ?: return null

            val manifestText = manifestFile.readText(Charsets.UTF_8)
            val configText = try {
                parseManifestForConfig(manifestDirPath, manifestText)
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse Steam Input manifest config at ${manifestFile.path}")
                return null
            }
            return configText ?: manifestText
        }

        private fun parseManifestForConfig(
            manifestDirPath: String,
            manifestText: String,
        ): String? {
            return try {
                val kv = KeyValue.loadFromString(manifestText) ?: return null
                val actionManifest = if (kv.name?.equals("Action Manifest", ignoreCase = true) == true) {
                    kv
                } else {
                    kv["Action Manifest"]
                }
                if (actionManifest === KeyValue.INVALID) return null

                val configs = actionManifest["configurations"]
                if (configs === KeyValue.INVALID || configs.children.isEmpty()) {
                    throw IllegalStateException("No configurations found in Action Manifest")
                }

                val preferredControllers = listOf(
                    "controller_xboxone",
                    "controller_steamcontroller_gordon",
                    "controller_generic",
                    "controller_xbox360",
                )

                for (controllerType in preferredControllers) {
                    val controllerBlock = configs[controllerType]
                    if (controllerBlock === KeyValue.INVALID) continue

                    for (entry in controllerBlock.children) {
                        val pathNode = entry["path"]
                        val configPath = pathNode.asString().orEmpty()
                        if (pathNode === KeyValue.INVALID || configPath.isEmpty()) continue

                        val configFile = resolvePathCaseInsensitive(manifestDirPath, configPath)
                            ?: continue
                        return configFile.readText(Charsets.UTF_8)
                    }
                }

                throw IllegalStateException("No valid controller configuration found in Action Manifest")
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse Steam Input manifest config")
                null
            }
        }

        private fun resolvePathCaseInsensitive(
            baseDirPath: String,
            relativePath: String,
        ): File? {
            val directFile = File(baseDirPath, relativePath)
            if (directFile.exists()) return directFile

            var currentDir = File(baseDirPath)
            if (!currentDir.exists() || !currentDir.isDirectory) return null

            val segments = relativePath.split('/', '\\').filter { it.isNotEmpty() }
            for ((index, segment) in segments.withIndex()) {
                val entries = currentDir.listFiles() ?: return null
                val matched = entries.firstOrNull {
                    it.name.equals(segment, ignoreCase = true)
                } ?: return null

                if (index == segments.lastIndex) {
                    return matched
                }

                if (!matched.isDirectory) return null
                currentDir = matched
            }

            return null
        }

        private fun readBuiltInSteamInputTemplate(fileName: String): String? {
            val assets = instance?.assets ?: return null
            return runCatching {
                assets.open("steaminput/$fileName").use { stream ->
                    stream.readBytes().toString(Charsets.UTF_8)
                }
            }.getOrNull()
        }

        private fun readDownloadedSteamInputTemplate(appId: Int): String? {
            val configFile = File(getAppDirPath(appId), STEAM_CONTROLLER_CONFIG_FILENAME)
            if (!configFile.exists()) return null
            return configFile.readText(Charsets.UTF_8)
        }

        fun resolveSteamControllerVdfText(appId: Int): String? {
            val config = getAppInfoOf(appId)?.config ?: return null
            return when (config.steamControllerTemplateIndex) {
                1 -> readDownloadedSteamInputTemplate(appId)
                13 -> {
                    val manifestFile = resolveSteamInputManifestFile(appId, getAppDirPath(appId))
                        ?: return null
                    loadConfigFromManifest(manifestFile)
                }
                2, 12 -> readBuiltInSteamInputTemplate("controller_xboxone_gamepad_fps.vdf")
                6 -> readBuiltInSteamInputTemplate("controller_xboxone_wasd.vdf")
                4, 5 -> readBuiltInSteamInputTemplate("gamepad_joystick.vdf")
                else -> readBuiltInSteamInputTemplate("gamepad+mouse.vdf")
            }
        }

        fun downloadApp(
            appId: Int,
            downloadableDepots: Map<Int, DepotInfo>,
            userSelectedDlcAppIds: List<Int>,
            branch: String,
            containerLanguage: String,
            isUpdateOrVerify: Boolean,
        ): DownloadInfo? {
            val appDirPath = getAppDirPath(appId)

            // Enforce Wi-Fi-only downloads
            if (PrefManager.downloadOnWifiOnly && instance?.isWifiConnected == false) {
                instance?.notificationHelper?.notify("Not connected to Wi‑Fi/LAN")
                return null
            }
            if (downloadJobs.contains(appId)) return getAppDownloadInfo(appId)
            Timber.d("depots is empty? " + downloadableDepots.isEmpty())
            if (downloadableDepots.isEmpty()) return null

            val indirectDlcAppIds = getDownloadableDlcAppsOf(appId).orEmpty().map { it.id }

            // Depots from Main game
            val mainDepots = getMainAppDepots(appId, containerLanguage)
            var mainAppDepots = mainDepots.filter { (_, depot) ->
                depot.dlcAppId == INVALID_APP_ID
            } + mainDepots.filter { (_, depot) ->
                userSelectedDlcAppIds.contains(depot.dlcAppId) && depot.manifests.isNotEmpty()
            }

            // Depots from DLC App
            val dlcAppDepots = downloadableDepots.filter { (_, depot) ->
                !mainAppDepots.map { it.key }.contains(depot.depotId) &&
                userSelectedDlcAppIds.contains(depot.dlcAppId) && indirectDlcAppIds.contains(depot.dlcAppId) && depot.manifests.isNotEmpty()
            }

            // Remove depots that are already downloaded (not for update/verify)
            val appInfo = getInstalledApp(appId)
            if (appInfo != null && !isUpdateOrVerify) {
                mainAppDepots = mainAppDepots.filter { it.key !in appInfo.downloadedDepots }
            }

            // Combine main app and DLC depots
            val selectedDepots = mainAppDepots + dlcAppDepots

            val downloadingAppIds = CopyOnWriteArrayList<Int>()
            val calculatedDlcAppIds = CopyOnWriteArrayList<Int>()

            userSelectedDlcAppIds.forEach { dlcAppId ->
                if (dlcAppDepots.filter { (_, depot) -> depot.dlcAppId == dlcAppId }.isNotEmpty()) {
                    downloadingAppIds.add(dlcAppId)
                    calculatedDlcAppIds.add(dlcAppId)
                }
            }

            // Add main app ID if there are main app depots
            if (mainAppDepots.isNotEmpty()) {
                downloadingAppIds.add(appId)
            }

            // There are some apps, the dlc depots does not have dlcAppId in the data, need to set it back
            val mainAppDlcIds = getMainAppDlcIdsWithoutProperDepotDlcIds(appId)

            // If there are no DLC depots, download the main app only
            if (dlcAppDepots.isEmpty()) {
                // Because all dlcIDs are coming from main depots, need to add the dlcID to main app in order to save it to db after finish download
                mainAppDlcIds.addAll(mainAppDepots.filter { it.value.dlcAppId != INVALID_APP_ID }.map { it.value.dlcAppId }.distinct())

                // Refresh id List, so only main app is downloaded
                calculatedDlcAppIds.clear()
                downloadingAppIds.clear()
                downloadingAppIds.add(appId)
            }

            Timber.i("selectedDepots is empty? " + selectedDepots.isEmpty())

            if (selectedDepots.isEmpty()) return null

            Timber.i("Starting download for $appId")
            Timber.i("App contains ${mainAppDepots.size} depot(s): ${mainAppDepots.keys}")
            Timber.i("DLC contains ${dlcAppDepots.size} depot(s): ${dlcAppDepots.keys}")
            Timber.i("downloadingAppIds: $downloadingAppIds")

            // Save downloading app info
            runBlocking {
                instance?.downloadingAppInfoDao?.insert(
                    DownloadingAppInfo(
                        appId,
                        dlcAppIds = userSelectedDlcAppIds
                    ),
                )
            }

            val info = DownloadInfo(selectedDepots.size, appId, downloadingAppIds).also { di ->
                di.setPersistencePath(appDirPath)
                // Set weights for each depot based on manifest sizes
                val sizes = selectedDepots.map { (_, depot) ->
                    val mInfo = depot.manifests[branch]
                        ?: depot.encryptedManifests[branch]
                        ?: return@map 1L
                    (mInfo.size ?: 1).toLong()
                }
                sizes.forEachIndexed { i, bytes -> di.setWeight(i, bytes) }

                // Total expected size (used for ETA based on recent download speed)
                val totalBytes = sizes.sum()
                di.setTotalExpectedBytes(totalBytes)

                // Load persisted bytes downloaded value on resume
                val persistedBytes = di.loadPersistedBytesDownloaded(appDirPath)
                if (persistedBytes > 0L) {
                    di.initializeBytesDownloaded(persistedBytes)
                    Timber.i("Resumed download: initialized with $persistedBytes bytes")
                }

                val downloadJob = instance!!.scope.launch {
                    try {
                        // Get licenses from database
                        val licenses = getLicensesFromDb()
                        if (licenses.isEmpty()) {
                            Timber.w("No licenses available for download")
                            return@launch
                        }

                        // Some notes here:
                        // Write should always be 1 in mobile device, as normally it does not use a SSD for storage
                        // And to have maximum throughput, set downloadRatio = decompressRatio = 1.0 x CPU Cores
                        var downloadRatio = 0.0
                        var decompressRatio = 0.0

                        when (PrefManager.downloadSpeed) {
                            8 -> {
                                downloadRatio = 0.6
                                decompressRatio = 0.2
                            }
                            16 -> {
                                downloadRatio = 1.2
                                decompressRatio = 0.4
                            }
                            24 -> {
                                downloadRatio = 1.5
                                decompressRatio = 0.5
                            }
                            32 -> {
                                downloadRatio = 2.4
                                decompressRatio = 0.8
                            }
                        }

                        val cpuCores = Runtime.getRuntime().availableProcessors()
                        val maxDownloads = (cpuCores * downloadRatio).toInt().coerceAtLeast(1)
                        val maxDecompress = (cpuCores * decompressRatio).toInt().coerceAtLeast(1)

                        Timber.i("CPU Cores: $cpuCores")
                        Timber.i("maxDownloads: $maxDownloads")
                        Timber.i("maxDecompress: $maxDecompress")

                        // Create DepotDownloader instance
                        val depotDownloader = DepotDownloader(
                            instance!!.steamClient!!,
                            licenses,
                            debug = false,
                            androidEmulation = true,
                            maxDownloads = maxDownloads,
                            maxDecompress = maxDecompress,
                            parentJob = coroutineContext[Job],
                            autoStartDownload = false,
                        )

                        // Create listeners for DLC apps
                        val depotIdToIndex = selectedDepots.keys.mapIndexed { index, depotId -> depotId to index }.toMap()
                        val listener = AppDownloadListener(di, depotIdToIndex)
                        depotDownloader.addListener(listener)

                        if (mainAppDepots.isNotEmpty()) {
                            // Create mapping from depotId to index for progress tracking
                            val mainAppDepotIds = mainAppDepots.keys.sorted()

                            // Create AppItem with only mandatory appId
                            val mainAppItem = AppItem(
                                appId,
                                installDirectory = getAppDirPath(appId),
                                depot = mainAppDepotIds,
                            )

                            // Add item to downloader
                            depotDownloader.add(mainAppItem)
                        }

                        // Create AppItem for each DLC app
                        calculatedDlcAppIds.forEach { dlcAppId ->
                            val dlcDepots = selectedDepots.filter { it.value.dlcAppId == dlcAppId }
                            val dlcDepotIds = dlcDepots.keys.sorted()

                            val dlcAppItem = AppItem(
                                dlcAppId,
                                installDirectory = getAppDirPath(appId),
                                depot = dlcDepotIds
                            )

                            depotDownloader.add(dlcAppItem)
                        }

                        val appConfig = getAppInfoOf(appId)?.config
                        if (appConfig?.steamControllerTemplateIndex == 1) {
                            val controllerConfig = appConfig.steamControllerConfigDetails
                                .let { selectSteamControllerConfig(it) }

                            if (controllerConfig != null) {
                                val appDirPath = getAppDirPath(appId)
                                val publishedFileId = controllerConfig.publishedFileId

                                runCatching {
                                    // Build POST request to Steam GetPublishedFileDetails API
                                    val requestBody = FormBody.Builder()
                                        .add("itemcount", "1")
                                        .add("publishedfileids[0]", publishedFileId.toString())
                                        .build()

                                    val request = Request.Builder()
                                        .url(
                                            "https://api.steampowered.com/" +
                                                "ISteamRemoteStorage/GetPublishedFileDetails/v1"
                                        )
                                        .post(requestBody)
                                        .build()

                                    Net.http.newCall(request).execute().use { response ->
                                        if (!response.isSuccessful) {
                                            Timber.w(
                                                "Failed to get steam controller config details " +
                                                    "for ${publishedFileId}: ${response.code}",
                                            )
                                            return@use
                                        }

                                        val responseBody = response.body?.string()
                                        if (responseBody.isNullOrEmpty()) {
                                            Timber.w(
                                                "Empty response body for steam controller config " +
                                                    publishedFileId,
                                            )
                                            return@use
                                        }

                                        // Parse JSON object response
                                        val responseJson = JSONObject(responseBody)
                                        val responseData = responseJson.optJSONObject("response")
                                        if (responseData == null) {
                                            Timber.w(
                                                "Steam controller config ${publishedFileId} " +
                                                    "missing response data",
                                            )
                                            return@use
                                        }

                                        val result = responseData.optInt("result", 0)
                                        val resultCount = responseData.optInt("resultcount", 0)
                                        if (result != 1 || resultCount < 1) {
                                            Timber.w(
                                                "Steam controller config ${publishedFileId} " +
                                                    "returned result=$result resultcount=$resultCount",
                                            )
                                            return@use
                                        }

                                        val fileDetails = responseData
                                            .optJSONArray("publishedfiledetails")
                                            ?.optJSONObject(0)
                                        if (fileDetails == null) {
                                            Timber.w(
                                                "Steam controller config ${publishedFileId} " +
                                                    "missing publishedfiledetails",
                                            )
                                            return@use
                                        }

                                        val fileUrl = fileDetails.optString("file_url", "").trim()

                                        if (fileUrl.isEmpty()) {
                                            Timber.w(
                                                "Steam controller config ${publishedFileId} " +
                                                    "missing fileUrl",
                                            )
                                            return@use
                                        }

                                        val configFile = File(appDirPath, STEAM_CONTROLLER_CONFIG_FILENAME)

                                        // Download the file
                                        val downloadRequest = Request.Builder()
                                            .url(fileUrl)
                                            .get()
                                            .build()

                                        Net.http.newCall(downloadRequest).execute().use { downloadResponse ->
                                            if (!downloadResponse.isSuccessful) {
                                                Timber.w(
                                                    "Failed to download steam controller config " +
                                                        "${publishedFileId}: ${downloadResponse.code}",
                                                )
                                                return@use
                                            }

                                            val downloadBody = downloadResponse.body
                                            if (downloadBody == null) {
                                                Timber.w(
                                                    "Empty body for steam controller config " +
                                                        publishedFileId,
                                                )
                                                return@use
                                            }

                                            configFile.outputStream().use { output ->
                                                downloadBody.byteStream().use { input ->
                                                    input.copyTo(output)
                                                }
                                            }

                                            Timber.i(
                                                "Downloaded steam controller config " +
                                                    "${publishedFileId} to ${configFile.path}",
                                            )
                                        }
                                    }
                                }.onFailure { error ->
                                    Timber.w(
                                        error,
                                        "Steam controller config download failed for " +
                                            publishedFileId,
                                    )
                                }
                            }
                        }

                        // Signal that no more items will be added
                        depotDownloader.finishAdding()

                        // Start Download
                        depotDownloader.startDownloading()

                        Timber.i("Downloading game to " + defaultAppInstallPath)

                        // Wait for completion
                        depotDownloader.getCompletion().await()

                        // Close the downloader
                        depotDownloader.close()

                        // Complete app download
                        if (mainAppDepots.isNotEmpty()) {
                            val mainAppDepotIds = mainAppDepots.keys.sorted()
                            completeAppDownload(di, appId, mainAppDepotIds, mainAppDlcIds, appDirPath)
                        }

                        // Complete dlc app download
                        calculatedDlcAppIds.forEach { dlcAppId ->
                            val dlcDepots = selectedDepots.filter { it.value.dlcAppId == dlcAppId }
                            val dlcDepotIds = dlcDepots.keys.sorted()
                            completeAppDownload(di, dlcAppId, dlcDepotIds, emptyList(), appDirPath)
                        }

                        // Remove the job here
                        removeDownloadJob(appId)

                        // Remove the downloading app info
                        runBlocking {
                            instance?.downloadingAppInfoDao?.deleteApp(appId)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Download failed for app $appId")
                        di.persistProgressSnapshot()
                        // Mark all depots as failed
                        selectedDepots.keys.sorted().forEachIndexed { idx, _ ->
                            di.setWeight(idx, 0)
                            di.setProgress(1f, idx)
                        }
                        removeDownloadJob(appId)
                    }
                }
                downloadJob.invokeOnCompletion { throwable ->
                    if (throwable is kotlinx.coroutines.CancellationException) {
                        Timber.d(throwable, "Download canceled for app $appId")
                        removeDownloadJob(appId)
                    }
                }
                di.setDownloadJob(downloadJob)
            }

            downloadJobs[appId] = info
            notifyDownloadStarted(appId)
            return info
        }

        private suspend fun completeAppDownload(
            downloadInfo: DownloadInfo,
            downloadingAppId: Int,
            entitledDepotIds: List<Int>,
            selectedDlcAppIds: List<Int>,
            appDirPath: String,
        ) {
            Timber.i("Item $downloadingAppId download completed, saving database")

            // Update database
            val appInfo = instance?.appInfoDao?.getInstalledApp(downloadingAppId)

            // Update Saved AppInfo
            if (appInfo != null) {
                val updatedDownloadedDepots = (appInfo.downloadedDepots + entitledDepotIds).distinct()
                val updatedDlcDepots = (appInfo.dlcDepots + selectedDlcAppIds).distinct()

                instance?.appInfoDao?.update(
                    AppInfo(
                        downloadingAppId,
                        isDownloaded = true,
                        downloadedDepots = updatedDownloadedDepots.sorted(),
                        dlcDepots = updatedDlcDepots.sorted(),
                    ),
                )
            } else {
                instance?.appInfoDao?.insert(
                    AppInfo(
                        downloadingAppId,
                        isDownloaded = true,
                        downloadedDepots = entitledDepotIds.sorted(),
                        dlcDepots = selectedDlcAppIds.sorted(),
                    ),
                )
            }

            // Remove completed appId from downloadInfo.dlcAppIds
            downloadInfo.downloadingAppIds.removeIf { it == downloadingAppId }

            // All downloading appIds are removed
            if (downloadInfo.downloadingAppIds.isEmpty()) {
                // Handle completion: add markers
                withContext(Dispatchers.IO) {
                    MarkerUtils.addMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
                    MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
                    MarkerUtils.removeMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED)
                }
                PluviaApp.events.emit(AndroidEvent.LibraryInstallStatusChanged(downloadInfo.gameId))

                // Clear persisted bytes file on successful completion
                downloadInfo.clearPersistedBytesDownloaded(appDirPath)
            }
        }

        /**
         * Listener for download progress and completion events from DepotDownloader
         */
        private class AppDownloadListener(
            private val downloadInfo: DownloadInfo,
            private val depotIdToIndex: Map<Int, Int>,
        ) : IDownloadListener {
            // Track cumulative uncompressed bytes per depot to calculate deltas
            // (uncompressedBytes from onChunkCompleted is cumulative per depot)
            private val depotCumulativeUncompressedBytes = mutableMapOf<Int, Long>()
            override fun onItemAdded(item: DownloadItem) {
                Timber.d("Item ${item.appId} added to queue")
            }

            override fun onDownloadStarted(item: DownloadItem) {
                Timber.i("Item ${item.appId} download started")
            }

            override fun onDownloadCompleted(item: DownloadItem) {
                Timber.i("Item ${item.appId} download completed")
            }

            override fun onDownloadFailed(item: DownloadItem, error: Throwable) {
                Timber.e(error, "Item ${item.appId} failed to download")
                downloadInfo.failedToDownload()

                // Remove the downloading app info
                runBlocking {
                    instance?.downloadingAppInfoDao?.deleteApp(downloadInfo.gameId)
                }

                removeDownloadJob(downloadInfo.gameId)
                instance?.let { service ->
                    service.scope.launch(Dispatchers.Main) {
                        Toast.makeText(
                            service.applicationContext,
                            service.getString(R.string.download_failed_try_again),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }

            override fun onStatusUpdate(message: String) {
                Timber.d("Download status: $message")
                downloadInfo.updateStatusMessage(message)
            }

            override fun onChunkCompleted(
                depotId: Int,
                depotPercentComplete: Float,
                compressedBytes: Long,
                uncompressedBytes: Long,
            ) {
                val isFirstCallForDepot = !depotCumulativeUncompressedBytes.containsKey(depotId)

                // uncompressedBytes is cumulative per depot, so calculate delta
                val previousBytes = depotCumulativeUncompressedBytes[depotId] ?: 0L
                val deltaBytes = uncompressedBytes - previousBytes
                depotCumulativeUncompressedBytes[depotId] = uncompressedBytes

                if (deltaBytes > 0L) {
                    // Normal case: add the delta
                    downloadInfo.updateBytesDownloaded(deltaBytes, System.currentTimeMillis())
                }

                depotIdToIndex[depotId]?.let { index ->
                    downloadInfo.setProgress(depotPercentComplete, index)
                }

                // Persist progress snapshot
                downloadInfo.persistProgressSnapshot()
            }

            override fun onDepotCompleted(depotId: Int, compressedBytes: Long, uncompressedBytes: Long) {
                Timber.i("Depot $depotId completed (compressed: $compressedBytes, uncompressed: $uncompressedBytes)")

                // Ensure we capture any remaining bytes
                val previousBytes = depotCumulativeUncompressedBytes[depotId] ?: 0L
                val deltaBytes = uncompressedBytes - previousBytes
                depotCumulativeUncompressedBytes[depotId] = uncompressedBytes

                if (deltaBytes > 0L) {
                    downloadInfo.updateBytesDownloaded(deltaBytes, System.currentTimeMillis())
                }

                depotIdToIndex[depotId]?.let { index ->
                    downloadInfo.setProgress(1f, index)
                }

                // Persist progress snapshot
                downloadInfo.persistProgressSnapshot()
            }
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
                if (isConnected) {
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
        }

        fun beginLaunchApp(
            appId: Int,
            parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
            ignorePendingOperations: Boolean = false,
            preferredSave: SaveLocation = SaveLocation.None,
            prefixToPath: (String) -> String,
            isOffline: Boolean = false,
            onProgress: ((message: String, progress: Float) -> Unit)? = null,
        ): Deferred<PostSyncInfo> = parentScope.async {
            if (isOffline || !isConnected) {
                return@async PostSyncInfo(SyncResult.UpToDate)
            }
            if (!tryAcquireSync(appId)) {
                Timber.w("Cannot launch app when sync already in progress for appId=$appId")
                return@async PostSyncInfo(SyncResult.InProgress)
            }

            try {
                var syncResult = PostSyncInfo(SyncResult.UnknownFail)

                val maxAttempts = 3
                for (attempt in 1..maxAttempts) {
                    try {
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
                                            onProgress = onProgress,
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
                        break
                    } catch (e: AsyncJobFailedException) {
                        if (attempt == maxAttempts) {
                            Timber.e(e, "Cloud sync failed after $maxAttempts attempts")
                            syncResult = PostSyncInfo(SyncResult.UnknownFail)
                        } else {
                            Timber.w("Cloud sync attempt $attempt failed (AsyncJobFailedException), retrying...")
                            delay(1000L * attempt)
                        }
                    }
                }

                return@async syncResult
            } finally {
                releaseSync(appId)
            }
        }

        suspend fun forceSyncUserFiles(
            appId: Int,
            prefixToPath: (String) -> String,
            preferredSave: SaveLocation = SaveLocation.None,
            parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
            overrideLocalChangeNumber: Long? = null,
        ): Deferred<PostSyncInfo> = parentScope.async {
            if (!tryAcquireSync(appId)) {
                Timber.w("Cannot force sync when sync already in progress for appId=$appId")
                return@async PostSyncInfo(SyncResult.InProgress)
            }

            try {
                var syncResult = PostSyncInfo(SyncResult.UnknownFail)

                val maxAttempts = 3
                for (attempt in 1..maxAttempts) {
                    try {
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
                        break
                    } catch (e: AsyncJobFailedException) {
                        if (attempt == maxAttempts) {
                            Timber.e(e, "Force cloud sync failed after $maxAttempts attempts")
                        } else {
                            Timber.w("Force cloud sync attempt $attempt failed (AsyncJobFailedException), retrying...")
                            delay(1000L * attempt)
                        }
                    }
                }

                return@async syncResult
            } finally {
                releaseSync(appId)
            }
        }

        suspend fun closeApp(appId: Int, isOffline: Boolean, prefixToPath: (String) -> String) = withContext(Dispatchers.IO) {
            async {
                if (isOffline || !isConnected) {
                    return@async
                }

                if (!tryAcquireSync(appId)) {
                    Timber.w("Cannot close app when sync already in progress for appId=$appId")
                    return@async
                }

                try {
                    val maxAttempts = 3
                    for (attempt in 1..maxAttempts) {
                        try {
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
                            break
                        } catch (e: AsyncJobFailedException) {
                            if (attempt == maxAttempts) {
                                Timber.e(e, "Close app sync failed after $maxAttempts attempts")
                            } else {
                                Timber.w("Close app sync attempt $attempt failed (AsyncJobFailedException), retrying...")
                                delay(1000L * attempt)
                            }
                        }
                    }
                } finally {
                    releaseSync(appId)
                }
            }
        }

        data class FileChanges(
            val filesDeleted: List<UserFileInfo>,
            val filesModified: List<UserFileInfo>,
            val filesCreated: List<UserFileInfo>,
        )

        /**
         * loginusers.vdf writer for the OAuth-style refresh-token flow introduced in 2024.
         *
         * @param steamId64    64-bit SteamID of the logged-in user
         * @param account      AccountName (same as you passed to logOn / poll result)
         * @param refreshToken Long-lived token you get from AuthSession / QR / credentials
         * @param accessToken  Optional – short-lived access token, Steam ignores it if absent
         * @param personaName  What the client shows in the drop-down; defaults to AccountName
         */
        internal fun getLoginUsersVdfOauth(
            steamId64: String,
            account: String,
            refreshToken: String,
            accessToken: String? = null,
            personaName: String = account,
        ): String {
            val epoch = System.currentTimeMillis() / 1_000

            val vdf = buildString {
                appendLine("\"users\"")
                appendLine("{")
                appendLine("    \"$steamId64\"")
                appendLine("    {")
                appendLine("        \"AccountName\"          \"$account\"")
                appendLine("        \"PersonaName\"          \"$personaName\"")
                appendLine("        \"RememberPassword\"     \"1\"")
                appendLine("        \"WantsOfflineMode\"     \"0\"")
                appendLine("        \"SkipOfflineModeWarning\"     \"0\"")
                appendLine("        \"AllowAutoLogin\"       \"1\"")
                appendLine("        \"MostRecent\"           \"1\"")
                appendLine("        \"Timestamp\"            \"$epoch\"")
                appendLine("    }")
                appendLine("}")
            }

            return vdf;
        }

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
                        this.clientOSType = EOSType.WinUnknown
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
                        this.deviceFriendlyName = SteamUtils.getMachineName(instance!!)
                        this.clientOSType = EOSType.WinUnknown
                        this.persistentSession = true
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
                        appDao.deleteAll()
                        changeNumbersDao.deleteAll()
                        fileChangeListsDao.deleteAll()
                        licenseDao.deleteAll()
                        encryptedAppTicketDao.deleteAll()
                        downloadingAppInfoDao.deleteAll()
                    }
                }
            }
        }

        private fun performLogOffDuties() {
            val username = PrefManager.username

            clearUserData()

            val event = SteamEvent.LoggedOut(username)
            PluviaApp.events.emit(event)

            // Cancel previous continuous jobs or else they will continue to run even after logout
            instance?.picsGetProductInfoJob?.cancel()
            instance?.picsChangesCheckerJob?.cancel()
            instance?.friendCheckerJob?.cancel()
        }

        suspend fun getOwnedGames(friendID: Long): List<OwnedGames> = withContext(Dispatchers.IO) {
            instance?._unifiedFriends!!.getOwnedGames(friendID)
        }

        // Add helper to detect if any downloads or cloud sync are in progress
        fun hasActiveOperations(): Boolean {
            val anySyncInProgress = syncInProgressApps.values.any { it.get() }
            return anySyncInProgress || downloadJobs.values.any { it.getProgress() < 1f }
        }

        // Should service auto-stop when idle (backgrounded)?
        var autoStopWhenIdle: Boolean = false

        suspend fun isUpdatePending(
            appId: Int,
            branch: String = "public",
        ): Boolean = withContext(Dispatchers.IO) {
            // Don't try if there's no internet
            if (!isConnected) return@withContext false

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
            val localSteamApp = getAppInfoOf(appId) ?: return@withContext true // not cached yet

            // ── 2. Compare manifest IDs of the depots we actually install.
            getDownloadableDepots(appId).keys.any { depotId ->
                val remoteManifest = remoteSteamApp.depots[depotId]?.manifests?.get(branch)
                val localManifest = localSteamApp.depots[depotId]?.manifests?.get(branch)
                // If remote manifest is null, skip this depot (hack for Castle Crashers)
                if (remoteManifest == null) return@any false
                remoteManifest?.gid != localManifest?.gid
            }
        }

        suspend fun checkDlcOwnershipViaPICSBatch(dlcAppIds: Set<Int>): Set<Int> {
            if (dlcAppIds.isEmpty()) return emptySet()

            val steamApps = instance?._steamApps ?: return emptySet()

            try {
                // Step 1: Get access tokens for all DLC appIds at once
                val tokens = steamApps.picsGetAccessTokens(
                    appIds = dlcAppIds.toList(),
                    packageIds = emptyList(),
                ).await()

                Timber.d("Access tokens response:")
                Timber.d("  - Granted tokens: ${tokens.appTokens.keys}")
                Timber.d("  - Denied tokens: ${tokens.appTokensDenied}")

                // Step 2: Filter to only appIds that have tokens (we own them)
                val ownedAppIds = tokens.appTokens.keys.filter { it in dlcAppIds }.toSet()

                Timber.d("Owned appIds (from tokens): $ownedAppIds")

                if (ownedAppIds.isEmpty()) {
                    Timber.w("No owned DLCs found via access tokens")
                    return emptySet()
                }

                // Step 3: Create PICSRequests for all owned appIds
                val picsRequests = ownedAppIds.map { appId ->
                    val token = tokens.appTokens[appId] ?: return@map null
                    PICSRequest(id = appId, accessToken = token)
                }.filterNotNull()

                Timber.d("Created ${picsRequests.size} PICS requests")

                if (picsRequests.isEmpty()) return emptySet()

                // Step 4: Query PICS for all apps at once (batch them)
                // Note: Steam has limits, so you might need to chunk if > 100 apps
                val chunkSize = 100
                val allOwnedAppIds = mutableSetOf<Int>()

                picsRequests.chunked(chunkSize).forEach { chunk ->
                    Timber.d("Querying PICS chunk with ${chunk.size} apps")
                    val callback = steamApps.picsGetProductInfo(
                        apps = chunk,
                        packages = emptyList(),
                    ).await()

                    // Collect all appIds that returned results
                    callback.results.forEach { picsCallback ->
                        val returnedAppIds = picsCallback.apps.keys
                        Timber.d("  PICS result: ${returnedAppIds.size} apps returned")
                        allOwnedAppIds.addAll(picsCallback.apps.keys)
                    }
                }

                Timber.i("Final owned DLC appIds: $allOwnedAppIds")
                Timber.i("Total owned: ${allOwnedAppIds.size} out of ${dlcAppIds.size} checked")

                return allOwnedAppIds
            } catch (e: Exception) {
                Timber.e(e, "Failed to check DLC ownership via PICS batch for ${dlcAppIds.size} appIds")
                return emptySet()
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
                java.util.concurrent.ConcurrentHashMap<Any, Any>(),   // replaces the HashMap
            )
        }

        PluviaApp.events.on<AndroidEvent.EndProcess, Unit>(onEndProcess)

        notificationHelper = NotificationHelper(applicationContext)
        // Setup Wi-Fi connectivity monitoring for download-on-WiFi-only
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // Determine initial Wi-Fi state
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        isWifiConnected = capabilities?.run {
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } == true
        // Register callback for Wi-Fi connectivity
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Timber.d("Wifi available")
                isWifiConnected = true
            }

            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities,
            ) {
                isWifiConnected = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            }

            override fun onLost(network: Network) {
                Timber.d("Wifi lost")
                isWifiConnected = false
                if (PrefManager.downloadOnWifiOnly) {
                    // Pause all ongoing downloads
                    for ((appId, info) in downloadJobs.entries.toList()) {
                        Timber.d("Cancelling job")
                        info.cancel()
                        PluviaApp.events.emit(AndroidEvent.DownloadPausedDueToConnectivity(appId))
                        removeDownloadJob(appId)
                    }
                    notificationHelper.notify("Download paused – waiting for Wi-Fi/LAN")
                }
            }
        }
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        // To view log messages in android logcat properly
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
                it.withConnectionTimeout(60000L)
                it.withHttpClient(
                    OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)   // Time to establish connection
                        .readTimeout(60, TimeUnit.SECONDS)      // Max inactivity between reads
                        .writeTimeout(30, TimeUnit.SECONDS)     // Time for writes
                        .build(),
                )
            }

            // create our steam client instance
            steamClient = SteamClient(configuration).apply {
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
                    add(subscribe(PlayingSessionStateCallback::class.java, ::onPlayingSessionState))
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

        // Persist download progress for all active downloads
        // This is a safety net for OS kills (unlikely but possible)
        downloadJobs.values.forEach { downloadInfo ->
            downloadInfo.persistProgressSnapshot()
        }

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
                } catch (e: NullPointerException) {
                    // I don't care
                } catch (e: Exception) {
                    Timber.e(e, "Failed to mark endpoint as bad:")
                }

                try {
                    steamClient!!.disconnect()
                } catch (e: NullPointerException) {
                    // I don't care
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

        LogManager.removeListener(logger)
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

        if (userSteamId?.isValid == true) {
            if (PrefManager.steamUserAccountId != userSteamId!!.accountID.toInt()) {
                PrefManager.steamUserAccountId = userSteamId!!.accountID.toInt()
                Timber.d("Saving logged in Steam accountID ${userSteamId!!.accountID.toInt()}")
            }
            val steamId64 = userSteamId!!.convertToUInt64()
            if (PrefManager.steamUserSteamId64 != steamId64) {
                PrefManager.steamUserSteamId64 = steamId64
                Timber.d("Saving logged in Steam ID64 $steamId64")
            }
        }

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

                picsChangesCheckerJob = continuousPICSChangesChecker()
                picsGetProductInfoJob = continuousPICSGetProductInfo()

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

    private fun onPlayingSessionState(callback: PlayingSessionStateCallback) {
        Timber.d("onPlayingSessionState called with isPlayingBlocked = " + callback.isPlayingBlocked)
        _isPlayingBlocked.value = callback.isPlayingBlocked
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun onPersonaStateReceived(callback: PersonaStateCallback) {
        // Ignore accounts that arent individuals
        if (!callback.friendId.isIndividualAccount) {
            return
        }

        // Ignore states where the name is blank.
        if (callback.playerName.isEmpty()) {
            return
        }

        // Timber.d("Persona state received: ${callback.name}")

        scope.launch {
            db.withTransaction {
                // Send off an event if we change states.
                if (callback.friendId == steamClient!!.steamID) {
                    Timber.d("Local persona state received: ${callback.playerName}")

                    val avatarHash = callback.avatarHash.toHexString()
                    val playerName = callback.playerName

                    // Update local state flow
                    _localPersona.update {
                        it.copy(
                            avatarHash = avatarHash,
                            name = playerName,
                            state = callback.personaState,
                            gameAppID = callback.gamePlayedAppId,
                            gameName = appDao.findApp(callback.gamePlayedAppId)?.name ?: callback.gameName,
                        )
                    }

                    // Cache local persona
                    PrefManager.steamUserAvatarHash = avatarHash
                    PrefManager.steamUserName = playerName

                    val event = SteamEvent.PersonaStateReceived(localPersona.value)
                    PluviaApp.events.emit(event)
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

                // Store raw licenses for DepotDownloader - each license in its own row
                licenses = callback.licenseList
                cachedLicenseDao.deleteAll()
                val cachedLicenses = callback.licenseList.map { license ->
                    CachedLicense(licenseJson = LicenseSerializer.serializeLicense(license))
                }
                cachedLicenseDao.insertAll(cachedLicenses)

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
    private fun continuousPICSChangesChecker(): Job = scope.launch {
        while (isActive && isLoggedIn) {
            // Initial delay before each check
            delay(60.seconds)

            PICSChangesCheck()
        }
    }

    private fun PICSChangesCheck() {
        scope.launch {
            ensureActive()

            try {
                val changesSince = _steamApps!!.picsGetChangesSince(
                    lastChangeNumber = PrefManager.lastPICSChangeNumber,
                    sendAppChangeList = true,
                    sendPackageChangelist = true,
                ).await()

                if (PrefManager.lastPICSChangeNumber == changesSince.currentChangeNumber) {
                    Timber.w("Change number was the same as last change number, skipping")
                    return@launch
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
                            ensureActive()
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

                        ensureActive()

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
            } catch (e: AsyncJobFailedException) {
                Timber.w("AsyncJobFailedException, skipping")
            }
        }
    }

    /**
     * A buffered flow to parse so many PICS requests in a given moment.
     */
    private fun continuousPICSGetProductInfo(): Job = scope.launch {
        // Launch both coroutines within this parent job
        launch {
            appPicsChannel.receiveAsFlow()
                .filter { it.isNotEmpty() }
                .buffer(capacity = MAX_PICS_BUFFER, onBufferOverflow = BufferOverflow.SUSPEND)
                .collect { appRequests ->
                    Timber.d("Processing ${appRequests.size} app PICS requests")

                    ensureActive()
                    if (!isLoggedIn) return@collect
                    val steamApps = instance?._steamApps ?: return@collect

                    val callback = steamApps.picsGetProductInfo(
                        apps = appRequests,
                        packages = emptyList(),
                    ).await()

                    callback.results.forEachIndexed { index, picsCallback ->
                        Timber.d(
                            "onPicsProduct: ${index + 1} of ${callback.results.size}" +
                                    "\n\tReceived PICS result of ${picsCallback.apps.size} app(s)." +
                                    "\n\tReceived PICS result of ${picsCallback.packages.size} package(s).",
                        )

                        ensureActive()
                        val steamAppsMap = picsCallback.apps.values.mapNotNull { app ->
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

                        if (steamAppsMap.isNotEmpty()) {
                            Timber.i("Inserting ${steamAppsMap.size} PICS apps to database")
                            db.withTransaction {
                                appDao.insertAll(steamAppsMap)
                            }
                        }
                    }
                }
        }

        launch {
            packagePicsChannel.receiveAsFlow()
                .filter { it.isNotEmpty() }
                .buffer(capacity = MAX_PICS_BUFFER, onBufferOverflow = BufferOverflow.SUSPEND)
                .collect { packageRequests ->
                    Timber.d("Processing ${packageRequests.size} package PICS requests")

                    ensureActive()
                    if (!isLoggedIn) return@collect
                    val steamApps = instance?._steamApps ?: return@collect

                    val callback = steamApps.picsGetProductInfo(
                        apps = emptyList(),
                        packages = packageRequests,
                    ).await()

                    callback.results.forEach { picsCallback ->
                        // Don't race the queue.
                        if (!isLoggedIn) return@collect
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

                        try {
                            // TODO: This could be an issue. (Stalling)
                            steamApps.picsGetAccessTokens(
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
                        } catch (e: AsyncJobFailedException) {
                            Timber.w("Could not get PICS product info $e")
                        }
                    }
                }
        }
    }

    /**
     * Get encrypted app ticket for an app, with 30-minute caching.
     * Returns the serialized protobuf bytes, or null if unavailable.
     */
    suspend fun getEncryptedAppTicket(appId: Int): ByteArray? {
        return try {
            // Check database for existing ticket less than 30 minutes old
            val cachedTicket = encryptedAppTicketDao.getByAppId(appId)
            val now = System.currentTimeMillis()
            val thirtyMinutes = 30 * 60 * 1000L

            if (cachedTicket != null && (now - cachedTicket.timestamp) < thirtyMinutes) {
                Timber.d("Using cached encrypted app ticket protobuf for app $appId")
                return cachedTicket.encryptedTicket
            }

            // Request new ticket from Steam
            val steamApps = instance?._steamApps ?: null
            val response = try {
                withTimeout(5_000) {
                    steamApps?.requestEncryptedAppTicket(appId)?.await()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to request encrypted app ticket for app $appId")
                return null
            }

            if (response?.result != EResult.OK || response.encryptedAppTicket == null) {
                Timber.w("Failed to get encrypted app ticket for app $appId: ${response?.result}")
                return null
            }

            // Extract all fields from the protobuf message
            val ticketProto = response.encryptedAppTicket
            val ticket = EncryptedAppTicket(
                appId = appId,
                result = response.result.code(),
                ticketVersionNo = ticketProto!!.ticketVersionNo.toInt(),
                crcEncryptedTicket = ticketProto.crcEncryptedticket.toInt(),
                cbEncryptedUserData = ticketProto.cbEncrypteduserdata.toInt(),
                cbEncryptedAppOwnershipTicket = ticketProto.cbEncryptedAppownershipticket.toInt(),
                encryptedTicket = ticketProto.toByteArray(),
                timestamp = now,
            )

            // Store in database
            encryptedAppTicketDao.insert(ticket)
            Timber.d("Stored new encrypted app ticket protobuf for app $appId")

            ticket.encryptedTicket
        } catch (e: Exception) {
            Timber.e(e, "Error getting encrypted app ticket for app $appId")
            null
        }
    }

    /**
     * Get encrypted app ticket as base64 encoded string, with 30-minute caching.
     * Returns the base64 encoded ticket, or null if unavailable.
     */
    suspend fun getEncryptedAppTicketBase64(appId: Int): String? {
        val ticket = getEncryptedAppTicket(appId) ?: return null
        return Base64.encodeToString(ticket, Base64.NO_WRAP)
    }
}
