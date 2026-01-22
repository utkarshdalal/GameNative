package app.gamenative

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.gamenative.enums.AppTheme
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.enums.HomeDestination
import app.gamenative.ui.enums.Orientation
import app.gamenative.ui.enums.PaneType
import com.materialkolor.PaletteStyle
import com.winlator.box86_64.Box86_64Preset
import com.winlator.container.Container
import com.winlator.core.DefaultVersion
import `in`.dragonbra.javasteam.enums.EPersonaState
import java.util.EnumSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * A universal Preference Manager that can be used anywhere within gamenative.
 * Note: King of ugly though.
 */
object PrefManager {

    private val Context.datastore by preferencesDataStore(
        name = "PluviaPreferences",
        corruptionHandler = ReplaceFileCorruptionHandler {
            Timber.e("Preferences (somehow got) corrupted, resetting.")
            emptyPreferences()
        },
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var dataStore: DataStore<Preferences>

    fun init(context: Context) {
        dataStore = context.datastore

        // Note: Should remove after a few release versions. we've moved to encrypted values.
        val oldPassword = stringPreferencesKey("password")
        removePref(oldPassword)

        val oldAccessToken = stringPreferencesKey("access_token")
        val oldRefreshToken = stringPreferencesKey("refresh_token")
        getPref(oldAccessToken, "").let {
            if (it.isNotEmpty()) {
                Timber.i("Converting old access token to encrypted")
                accessToken = it
                removePref(oldAccessToken)
            }
        }
        getPref(oldRefreshToken, "").let {
            if (it.isNotEmpty()) {
                Timber.i("Converting old refresh token to encrypted")
                refreshToken = it
                removePref(oldRefreshToken)
            }
        }
    }

    fun clearPreferences() {
        scope.launch {
            dataStore.edit { it.clear() }
        }
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        getPref(booleanPreferencesKey(key), defaultValue)

    fun getString(key: String, defaultValue: String): String =
        getPref(stringPreferencesKey(key), defaultValue)

    fun getFloat(key: String, defaultValue: Float): Float =
        getPref(floatPreferencesKey(key), defaultValue)

    fun setFloat(key: String, value: Float): Unit =
        setPref(floatPreferencesKey(key), value)

    @Suppress("SameParameterValue")
    private fun <T> getPref(key: Preferences.Key<T>, defaultValue: T): T = runBlocking {
        dataStore.data.first()[key] ?: defaultValue
    }

    @Suppress("SameParameterValue")
    private fun <T> setPref(key: Preferences.Key<T>, value: T) {
        scope.launch {
            dataStore.edit { pref -> pref[key] = value }
        }
    }

    private fun <T> removePref(key: Preferences.Key<T>) {
        scope.launch {
            dataStore.edit { pref -> pref.remove(key) }
        }
    }

    /* PICS */
    private val LAST_PICS_CHANGE_NUMBER = intPreferencesKey("last_pics_change_number")
    var lastPICSChangeNumber: Int
        get() = getPref(LAST_PICS_CHANGE_NUMBER, 0)
        set(value) {
            setPref(LAST_PICS_CHANGE_NUMBER, value)
        }

    /* Container Default Settings */
    private val SCREEN_SIZE = stringPreferencesKey("screen_size")
    var screenSize: String
        get() = getPref(SCREEN_SIZE, Container.DEFAULT_SCREEN_SIZE)
        set(value) {
            setPref(SCREEN_SIZE, value)
        }

    private val ENV_VARS = stringPreferencesKey("env_vars")
    var envVars: String
        get() = getPref(ENV_VARS, Container.DEFAULT_ENV_VARS)
        set(value) {
            setPref(ENV_VARS, value)
        }

    private val GRAPHICS_DRIVER = stringPreferencesKey("graphics_driver")
    var graphicsDriver: String
        get() = getPref(GRAPHICS_DRIVER, Container.DEFAULT_GRAPHICS_DRIVER)
        set(value) {
            setPref(GRAPHICS_DRIVER, value)
        }

    private val GRAPHICS_DRIVER_VERSION = stringPreferencesKey("graphics_driver_version")
    var graphicsDriverVersion: String
        get() = getPref(GRAPHICS_DRIVER_VERSION, "")
        set(value) {
            setPref(GRAPHICS_DRIVER_VERSION, value)
        }

    private val GRAPHICS_DRIVER_CONFIG = stringPreferencesKey("graphics_driver_config")
    var graphicsDriverConfig: String
        get() = getPref(GRAPHICS_DRIVER_CONFIG, Container.DEFAULT_GRAPHICSDRIVERCONFIG)
        set(value) {
            setPref(GRAPHICS_DRIVER_CONFIG, value)
        }

    private val SHARPNESS_EFFECT = stringPreferencesKey("sharpness_effect")
    var sharpnessEffect: String
        get() = getPref(SHARPNESS_EFFECT, "None")
        set(value) {
            setPref(SHARPNESS_EFFECT, value)
        }

    private val SHARPNESS_LEVEL = intPreferencesKey("sharpness_level")
    var sharpnessLevel: Int
        get() = getPref(SHARPNESS_LEVEL, 100)
        set(value) {
            setPref(SHARPNESS_LEVEL, value.coerceIn(0, 100))
        }

    private val SHARPNESS_DENOISE = intPreferencesKey("sharpness_denoise")
    var sharpnessDenoise: Int
        get() = getPref(SHARPNESS_DENOISE, 100)
        set(value) {
            setPref(SHARPNESS_DENOISE, value.coerceIn(0, 100))
        }

    private val CONTAINER_VARIANT = stringPreferencesKey("container_variant")
    var containerVariant: String
        get() = getPref(CONTAINER_VARIANT, Container.DEFAULT_VARIANT)
        set(value) {
            setPref(CONTAINER_VARIANT, value)
        }

    private val WINE_VERSION = stringPreferencesKey("wine_version")
    var wineVersion: String
        get() = getPref(WINE_VERSION, Container.DEFAULT_WINE_VERSION)
        set(value) {
            setPref(WINE_VERSION, value)
        }

    private val EMULATOR = stringPreferencesKey("emulator")
    var emulator: String
        get() = getPref(EMULATOR, Container.DEFAULT_EMULATOR)
        set(value) {
            setPref(EMULATOR, value)
        }

    private val FEXCORE_VERSION = stringPreferencesKey("fexcore_version")
    var fexcoreVersion: String
        get() = getPref(FEXCORE_VERSION, DefaultVersion.FEXCORE)
        set(value) {
            setPref(FEXCORE_VERSION, value)
        }

    private val FEXCORE_TSO_MODE = stringPreferencesKey("fexcore_tso_mode")
    var fexcoreTSOMode: String
        get() = getPref(FEXCORE_TSO_MODE, "Fast")
        set(value) {
            setPref(FEXCORE_TSO_MODE, value)
        }

    private val FEXCORE_X87_MODE = stringPreferencesKey("fexcore_x87_mode")
    var fexcoreX87Mode: String
        get() = getPref(FEXCORE_X87_MODE, "Fast")
        set(value) {
            setPref(FEXCORE_X87_MODE, value)
        }

    private val FEXCORE_MULTIBLOCK = stringPreferencesKey("fexcore_multiblock")
    var fexcoreMultiBlock: String
        get() = getPref(FEXCORE_MULTIBLOCK, "Disabled")
        set(value) {
            setPref(FEXCORE_MULTIBLOCK, value)
        }

    private val DXWRAPPER = stringPreferencesKey("dxwrapper")
    var dxWrapper: String
        get() = getPref(DXWRAPPER, Container.DEFAULT_DXWRAPPER)
        set(value) {
            setPref(DXWRAPPER, value)
        }

    private val DXWRAPPER_CONFIG = stringPreferencesKey("dxwrapperConfig")
    var dxWrapperConfig: String
        get() = getPref(DXWRAPPER_CONFIG, Container.DEFAULT_DXWRAPPERCONFIG)
        set(value) {
            setPref(DXWRAPPER_CONFIG, value)
        }

    private val AUDIO_DRIVER = stringPreferencesKey("audio_driver")
    var audioDriver: String
        get() = getPref(AUDIO_DRIVER, Container.DEFAULT_AUDIO_DRIVER)
        set(value) {
            setPref(AUDIO_DRIVER, value)
        }

    private val WIN_COMPONENTS = stringPreferencesKey("wincomponents")
    var winComponents: String
        get() = getPref(WIN_COMPONENTS, Container.DEFAULT_WINCOMPONENTS)
        set(value) {
            setPref(WIN_COMPONENTS, value)
        }

    private val DRIVES = stringPreferencesKey("drives")
    var drives: String
        get() = getPref(DRIVES, Container.DEFAULT_DRIVES)
        set(value) {
            setPref(DRIVES, value)
        }

    private val SHOW_FPS = booleanPreferencesKey("show_fps")
    var showFps: Boolean
        get() = getPref(SHOW_FPS, false)
        set(value) {
            setPref(SHOW_FPS, value)
        }

    private val LAUNCH_REAL_STEAM = booleanPreferencesKey("launch_real_steam")
    var launchRealSteam: Boolean
        get() = getPref(LAUNCH_REAL_STEAM, false)
        set(value) {
            setPref(LAUNCH_REAL_STEAM, value)
        }

    private val FORCE_DLC = booleanPreferencesKey("force_dlc")
    var forceDlc: Boolean
        get() = getPref(FORCE_DLC, false)
        set(value) {
            setPref(FORCE_DLC, value)
        }

    private val USE_LEGACY_DRM = booleanPreferencesKey("use_legacy_drm")
    var useLegacyDRM: Boolean
        get() = getPref(USE_LEGACY_DRM, false)
        set(value) {
            setPref(USE_LEGACY_DRM, value)
        }

    private val UNPACK_FILES = booleanPreferencesKey("unpack_files")
    var unpackFiles: Boolean
        get() = getPref(UNPACK_FILES, false)
        set(value) {
            setPref(UNPACK_FILES, value)
        }

    private val CPU_LIST = stringPreferencesKey("cpu_list")
    var cpuList: String
        get() = getPref(CPU_LIST, Container.getFallbackCPUList())
        set(value) {
            setPref(CPU_LIST, value)
        }

    private val CPU_LIST_WOW64 = stringPreferencesKey("cpu_list_wow64")
    var cpuListWoW64: String
        get() = getPref(CPU_LIST_WOW64, Container.getFallbackCPUListWoW64())
        set(value) {
            setPref(CPU_LIST_WOW64, value)
        }

    private val WOW64_MODE = booleanPreferencesKey("wow64_mode")
    var wow64Mode: Boolean
        get() = getPref(WOW64_MODE, true)
        set(value) {
            setPref(WOW64_MODE, value)
        }

    private val STARTUP_SELECTION = intPreferencesKey("startup_selection")
    var startupSelection: Int
        get() = getPref(STARTUP_SELECTION, Container.STARTUP_SELECTION_ESSENTIAL.toInt())
        set(value) {
            setPref(STARTUP_SELECTION, value)
        }

    private val CONTAINER_LANGUAGE = stringPreferencesKey("container_language")
    var containerLanguage: String
        get() = getPref(CONTAINER_LANGUAGE, "english")
        set(value) {
            setPref(CONTAINER_LANGUAGE, value)
        }

    private val BOX86_PRESET = stringPreferencesKey("box86_preset")
    var box86Preset: String
        get() = getPref(BOX86_PRESET, Box86_64Preset.COMPATIBILITY)
        set(value) {
            setPref(BOX86_PRESET, value)
        }

    private val BOX64_PRESET = stringPreferencesKey("box64_preset")
    var box64Preset: String
        get() = getPref(BOX64_PRESET, Box86_64Preset.COMPATIBILITY)
        set(value) {
            setPref(BOX64_PRESET, value)
        }

    private val FEXCORE_PRESET = stringPreferencesKey("fexcore_preset")
    var fexcorePreset: String
        get() = getPref(FEXCORE_PRESET, com.winlator.fexcore.FEXCorePreset.INTERMEDIATE)
        set(value) {
            setPref(FEXCORE_PRESET, value)
        }

    private val RENDERER = stringPreferencesKey("renderer")
    var renderer: String
        get() = getPref(RENDERER, "gl")
        set(value) {
            setPref(RENDERER, value)
        }

    private val CSMT = booleanPreferencesKey("csmt")
    var csmt: Boolean
        get() = getPref(CSMT, true)
        set(value) {
            setPref(CSMT, value)
        }

    private val VIDEO_PCI_DEVICE_ID = intPreferencesKey("videoPciDeviceID")
    var videoPciDeviceID: Int
        get() = getPref(VIDEO_PCI_DEVICE_ID, 1728)
        set(value) {
            setPref(VIDEO_PCI_DEVICE_ID, value)
        }

    private val OFFSCREEN_RENDERING_MODE = stringPreferencesKey("offScreenRenderingMode")
    var offScreenRenderingMode: String
        get() = getPref(OFFSCREEN_RENDERING_MODE, "fbo")
        set(value) {
            setPref(OFFSCREEN_RENDERING_MODE, value)
        }

    private val STRICT_SHADER_MATH = booleanPreferencesKey("strictShaderMath")
    var strictShaderMath: Boolean
        get() = getPref(STRICT_SHADER_MATH, true)
        set(value) {
            setPref(STRICT_SHADER_MATH, value)
        }

    private val USE_DRI3 = booleanPreferencesKey("useDRI3")
    var useDRI3: Boolean
        get() = getPref(USE_DRI3, true)
        set(value) {
            setPref(USE_DRI3, value)
        }

    private val VIDEO_MEMORY_SIZE = stringPreferencesKey("videoMemorySize")
    var videoMemorySize: String
        get() = getPref(VIDEO_MEMORY_SIZE, "2048")
        set(value) {
            setPref(VIDEO_MEMORY_SIZE, value)
        }

    private val MOUSE_WARP_OVERRIDE = stringPreferencesKey("mouseWarpOverride")
    var mouseWarpOverride: String
        get() = getPref(MOUSE_WARP_OVERRIDE, "disable")
        set(value) {
            setPref(MOUSE_WARP_OVERRIDE, value)
        }

    // Controller Input Defaults
    private val USE_STEAM_INPUT = booleanPreferencesKey("use_steam_input")
    var useSteamInput: Boolean
        get() = getPref(USE_STEAM_INPUT, false)
        set(value) {
            setPref(USE_STEAM_INPUT, value)
        }

    private val XINPUT_ENABLED = booleanPreferencesKey("xinput_enabled")
    var xinputEnabled: Boolean
        get() = getPref(XINPUT_ENABLED, true)
        set(value) {
            setPref(XINPUT_ENABLED, value)
        }

    private val DINPUT_ENABLED = booleanPreferencesKey("dinput_enabled")
    var dinputEnabled: Boolean
        get() = getPref(DINPUT_ENABLED, true)
        set(value) {
            setPref(DINPUT_ENABLED, value)
        }

    private val DINPUT_MAPPER_TYPE = intPreferencesKey("dinput_mapper_type")
    var dinputMapperType: Int
        get() = getPref(DINPUT_MAPPER_TYPE, 1)
        set(value) {
            setPref(DINPUT_MAPPER_TYPE, value)
        }

    // Disable Mouse Input (prevents external mouse events)
    private val DISABLE_MOUSE_INPUT = booleanPreferencesKey("disable_mouse_input")
    var disableMouseInput: Boolean
        get() = getPref(DISABLE_MOUSE_INPUT, false)
        set(value) {
            setPref(DISABLE_MOUSE_INPUT, value)
        }

    private val BOX_86_VERSION = stringPreferencesKey("box86_version")
    var box86Version: String
        get() = getPref(BOX_86_VERSION, DefaultVersion.BOX86)
        set(value) {
            setPref(BOX_86_VERSION, value)
        }

    private val BOX_64_VERSION = stringPreferencesKey("box64_version")
    var box64Version: String
        get() = getPref(BOX_64_VERSION, DefaultVersion.BOX64)
        set(value) {
            setPref(BOX_64_VERSION, value)
        }

    private val EXEC_ARGS = stringPreferencesKey("exec_args")
    var execArgs: String
        get() = getPref(EXEC_ARGS, "")
        set(value) {
            setPref(EXEC_ARGS, value)
        }


    /* Recent Crash Flag */
    private val RECENTLY_CRASHED = booleanPreferencesKey("recently_crashed")
    var recentlyCrashed: Boolean
        get() = getPref(RECENTLY_CRASHED, false)
        set(value) {
            setPref(RECENTLY_CRASHED, value)
        }

    /* Login Info */
    private val CELL_ID = intPreferencesKey("cell_id")
    private val CELL_ID_MANUALLY_SET = booleanPreferencesKey("cell_id_manually_set")

    var cellId: Int
        get() = getPref(CELL_ID, 0)
        set(value) {
            setPref(CELL_ID, value)
            if (value == 0) {
                setPref(CELL_ID_MANUALLY_SET, false)
            }
        }

    var cellIdManuallySet: Boolean
        get() = getPref(CELL_ID_MANUALLY_SET, false)
        set(value) {
            setPref(CELL_ID_MANUALLY_SET, value)
        }

    private val USER_NAME = stringPreferencesKey("user_name")
    var username: String
        get() = getPref(USER_NAME, "")
        set(value) {
            setPref(USER_NAME, value)
        }

    private val ACCESS_TOKEN_ENC = byteArrayPreferencesKey("access_token_enc")
    var accessToken: String
        get() {
            val encryptedBytes = getPref(ACCESS_TOKEN_ENC, ByteArray(0))
            return if (encryptedBytes.isEmpty()) {
                ""
            } else {
                val bytes = Crypto.decrypt(encryptedBytes)
                String(bytes)
            }
        }
        set(value) {
            val bytes = Crypto.encrypt(value.toByteArray())
            setPref(ACCESS_TOKEN_ENC, bytes)
        }

    private val REFRESH_TOKEN_ENC = byteArrayPreferencesKey("refresh_token_enc")
    var refreshToken: String
        get() {
            val encryptedBytes = getPref(REFRESH_TOKEN_ENC, ByteArray(0))
            return if (encryptedBytes.isEmpty()) {
                ""
            } else {
                val bytes = Crypto.decrypt(encryptedBytes)
                String(bytes)
            }
        }
        set(value) {
            val bytes = Crypto.encrypt(value.toByteArray())
            setPref(REFRESH_TOKEN_ENC, bytes)
        }

    // Special: Because null value.
    private val CLIENT_ID = longPreferencesKey("client_id")
    var clientId: Long?
        get() = runBlocking { dataStore.data.first()[CLIENT_ID] }
        set(value) {
            scope.launch {
                dataStore.edit { pref -> pref[CLIENT_ID] = value!! }
            }
        }

    private val LIBRARY_LAYOUT = intPreferencesKey("library_layout")
    var libraryLayout: PaneType
        get() {
            val value = getPref(LIBRARY_LAYOUT, PaneType.UNDECIDED.ordinal)
            return PaneType.entries.getOrNull(value) ?: PaneType.UNDECIDED
        }
        set(value) {
            setPref(LIBRARY_LAYOUT, value.ordinal)
        }

    private val LIBRARY_FILTER = intPreferencesKey("library_filter")
    var libraryFilter: EnumSet<AppFilter>
        get() {
            val value = getPref(LIBRARY_FILTER, AppFilter.toFlags(EnumSet.of(AppFilter.GAME, AppFilter.SHARED)))
            return AppFilter.fromFlags(value)
        }
        set(value) {
            setPref(LIBRARY_FILTER, AppFilter.toFlags(value))
        }

    /**
     * Get or Set the last known Persona State. See [EPersonaState]
     */
    private val PERSONA_STATE = intPreferencesKey("persona_state")
    var personaState: EPersonaState
        get() {
            val value = getPref(PERSONA_STATE, EPersonaState.Online.code())
            return EPersonaState.from(value)
        }
        set(value) {
            setPref(PERSONA_STATE, value.code())
        }

    private val STEAM_USER_ACCOUNT_ID = intPreferencesKey("steam_user_account_id")
    var steamUserAccountId: Int
        get() = getPref(STEAM_USER_ACCOUNT_ID, 0)
        set(value) {
            setPref(STEAM_USER_ACCOUNT_ID, value)
        }

    private val STEAM_USER_STEAM_ID_64 = longPreferencesKey("steam_user_steam_id_64")
    var steamUserSteamId64: Long
        get() = getPref(STEAM_USER_STEAM_ID_64, 0L)
        set(value) {
            setPref(STEAM_USER_STEAM_ID_64, value)
        }

    /**
     * Get or Set the last known avatar hash for the user.
     */
    private val STEAM_USER_AVATAR_HASH = stringPreferencesKey("steam_user_avatar_hash")
    var steamUserAvatarHash: String
        get() = getPref(STEAM_USER_AVATAR_HASH, "")
        set(value) {
            setPref(STEAM_USER_AVATAR_HASH, value)
        }

    /**
     * Get or Set the last known name for the user.
     */
    private val STEAM_USER_NAME = stringPreferencesKey("steam_user_name")
    var steamUserName: String
        get() = getPref(STEAM_USER_NAME, "")
        set(value) {
            setPref(STEAM_USER_NAME, value)
        }

    private val ALLOWED_ORIENTATION = intPreferencesKey("allowed_orientation")
    var allowedOrientation: EnumSet<Orientation>
        get() {
            val defaultValue = Orientation.toInt(
                EnumSet.of(Orientation.LANDSCAPE, Orientation.REVERSE_LANDSCAPE),
            )
            val value = getPref(ALLOWED_ORIENTATION, defaultValue)
            return Orientation.fromInt(value)
        }
        set(value) {
            setPref(ALLOWED_ORIENTATION, Orientation.toInt(value))
        }

    private val TIPPED = booleanPreferencesKey("tipped")
    var tipped: Boolean
        get() {
            val value = getPref(TIPPED, false)
            return value
        }
        set(value) {
            setPref(TIPPED, value)
        }

    private val APP_THEME = intPreferencesKey("app_theme")
    var appTheme: AppTheme
        get() {
            val value = getPref(APP_THEME, AppTheme.AUTO.ordinal)
            return AppTheme.entries.getOrNull(value) ?: AppTheme.AUTO
        }
        set(value) {
            setPref(APP_THEME, value.ordinal)
        }

    private val APP_THEME_PALETTE = intPreferencesKey("app_theme_palette")
    var appThemePalette: PaletteStyle
        get() {
            val value = getPref(APP_THEME_PALETTE, PaletteStyle.TonalSpot.ordinal)
            return PaletteStyle.entries.getOrNull(value) ?: PaletteStyle.TonalSpot
        }
        set(value) {
            setPref(APP_THEME_PALETTE, value.ordinal)
        }

    private val START_SCREEN = intPreferencesKey("start screen")
    var startScreen: HomeDestination
        get() {
            val value = getPref(START_SCREEN, HomeDestination.Library.ordinal)
            return HomeDestination.entries.getOrNull(value) ?: HomeDestination.Library
        }
        set(value) {
            setPref(START_SCREEN, value.ordinal)
        }

    private val FRIENDS_LIST_HEADER = stringPreferencesKey("friends_list_header")
    var friendsListHeader: Set<String>
        get() {
            val value = getPref(FRIENDS_LIST_HEADER, "[]")
            return Json.decodeFromString<Set<String>>(value)
        }
        set(value) {
            setPref(FRIENDS_LIST_HEADER, Json.encodeToString(value))
        }

    // NOTE: This should be removed once chat is considered stable.
    private val ACK_CHAT_PREVIEW = booleanPreferencesKey("ack_chat_preview")
    var ackChatPreview: Boolean
        get() = getPref(ACK_CHAT_PREVIEW, false)
        set(value) {
            setPref(ACK_CHAT_PREVIEW, value)
        }

    // Whether to open links internally with a webview or open externally with a user's browser.
    private val OPEN_WEB_LINKS_EXTERNALLY = booleanPreferencesKey("open_web_links_externally")
    var openWebLinksExternally: Boolean
        get() = getPref(OPEN_WEB_LINKS_EXTERNALLY, true)
        set(value) {
            setPref(OPEN_WEB_LINKS_EXTERNALLY, value)
        }

    // Whether to hide the Android status bar when not in a game (in game list, settings, etc.)
    private val HIDE_STATUS_BAR_WHEN_NOT_IN_GAME = booleanPreferencesKey("hide_status_bar_when_not_in_game")
    var hideStatusBarWhenNotInGame: Boolean
        get() = getPref(HIDE_STATUS_BAR_WHEN_NOT_IN_GAME, false)
        set(value) {
            setPref(HIDE_STATUS_BAR_WHEN_NOT_IN_GAME, value)
        }

    private val ITEMS_PER_PAGE = intPreferencesKey("items_per_page")
    var itemsPerPage: Int
        get() = getPref(ITEMS_PER_PAGE, 50)
        set(value) {
            setPref(ITEMS_PER_PAGE, value)
        }

    // App Source filters
    private val SHOW_STEAM_IN_LIBRARY = booleanPreferencesKey("show_steam_in_library")
    var showSteamInLibrary: Boolean
        get() = getPref(SHOW_STEAM_IN_LIBRARY, true)
        set(value) {
            setPref(SHOW_STEAM_IN_LIBRARY, value)
        }

    private val SHOW_CUSTOM_GAMES_IN_LIBRARY = booleanPreferencesKey("show_custom_games_in_library")
    var showCustomGamesInLibrary: Boolean
        get() = getPref(SHOW_CUSTOM_GAMES_IN_LIBRARY, true)
        set(value) {
            setPref(SHOW_CUSTOM_GAMES_IN_LIBRARY, value)
        }

    private val SHOW_GOG_IN_LIBRARY = booleanPreferencesKey("show_gog_in_library")
    var showGOGInLibrary: Boolean
        get() = getPref(SHOW_GOG_IN_LIBRARY, true)
        set(value) {
            setPref(SHOW_GOG_IN_LIBRARY, value)
        }

    // Game counts for skeleton loaders
    private val CUSTOM_GAMES_COUNT = intPreferencesKey("custom_games_count")
    var customGamesCount: Int
        get() = getPref(CUSTOM_GAMES_COUNT, 0)
        set(value) {
            setPref(CUSTOM_GAMES_COUNT, value)
        }

    private val STEAM_GAMES_COUNT = intPreferencesKey("steam_games_count")
    var steamGamesCount: Int
        get() = getPref(STEAM_GAMES_COUNT, 0)
        set(value) {
            setPref(STEAM_GAMES_COUNT, value)
        }

    private val GOG_GAMES_COUNT = intPreferencesKey("gog_games_count")
    var gogGamesCount: Int
        get() = getPref(GOG_GAMES_COUNT, 0)
        set(value) {
            setPref(GOG_GAMES_COUNT, value)
        }

    private val GOG_INSTALLED_GAMES_COUNT = intPreferencesKey("gog_installed_games_count")
    var gogInstalledGamesCount: Int
        get() = getPref(GOG_INSTALLED_GAMES_COUNT, 0)
        set(value) {
            setPref(GOG_INSTALLED_GAMES_COUNT, value)
        }

    // Show dialog when adding custom game folder
    private val SHOW_ADD_CUSTOM_GAME_DIALOG = booleanPreferencesKey("show_add_custom_game_dialog")
    var showAddCustomGameDialog: Boolean
        get() = getPref(SHOW_ADD_CUSTOM_GAME_DIALOG, true)
        set(value) {
            setPref(SHOW_ADD_CUSTOM_GAME_DIALOG, value)
        }

    // Whether to download games only over Wi-Fi.
    private val DOWNLOAD_ON_WIFI_ONLY = booleanPreferencesKey("download_on_wifi_only")
    var downloadOnWifiOnly: Boolean
        get() = getPref(DOWNLOAD_ON_WIFI_ONLY, true)
        set(value) {
            setPref(DOWNLOAD_ON_WIFI_ONLY, value)
        }

    // Maximum number of concurrent downloads (8=slow, 16=medium, 24=fast, 32=blazing)
    private val DOWNLOAD_SPEED = intPreferencesKey("download_speed")
    var downloadSpeed: Int
        get() = getPref(DOWNLOAD_SPEED, 24)
        set(value) {
            setPref(DOWNLOAD_SPEED, value)
        }

    private val USE_EXTERNAL_STORAGE = booleanPreferencesKey("use_external_storage")
    var useExternalStorage: Boolean
        get() = getPref(USE_EXTERNAL_STORAGE, false)
        set(value) {
            setPref(USE_EXTERNAL_STORAGE, value)
            setPref(EXTERNAL_STORAGE_PATH, "")
        }

    private val FETCH_STEAMGRIDDB_IMAGES = booleanPreferencesKey("fetch_steamgriddb_images")
    var fetchSteamGridDBImages: Boolean
        get() = getPref(FETCH_STEAMGRIDDB_IMAGES, true)
        set(value) {
            setPref(FETCH_STEAMGRIDDB_IMAGES, value)
        }

    private val EXTERNAL_STORAGE_PATH = stringPreferencesKey("external_storage_path")
    var externalStoragePath: String
        get() = getPref(EXTERNAL_STORAGE_PATH, "")
        set(value) {
            setPref(EXTERNAL_STORAGE_PATH, value)
        }

    // Custom Games root (additional paths). Default path is provided by the app at runtime and isn't stored here.
    private val CUSTOM_GAME_PATHS = stringPreferencesKey("custom_game_paths")
    var customGamePaths: Set<String>
        get() {
            val value = getPref(CUSTOM_GAME_PATHS, "[]")
            return try {
                Json.decodeFromString<Set<String>>(value)
            } catch (e: Exception) {
                emptySet()
            }
        }
        set(value) {
            setPref(CUSTOM_GAME_PATHS, Json.encodeToString(value))
        }

    private val CUSTOM_GAME_MANUAL_FOLDERS = stringPreferencesKey("custom_game_manual_folders")
    var customGameManualFolders: Set<String>
        get() {
            val value = getPref(CUSTOM_GAME_MANUAL_FOLDERS, "[]")
            return try {
                Json.decodeFromString<Set<String>>(value)
            } catch (e: Exception) {
                emptySet()
            }
        }
        set(value) {
            setPref(CUSTOM_GAME_MANUAL_FOLDERS, Json.encodeToString(value))
        }

    // Add new setting for Wine debug logging
    private val ENABLE_WINE_DEBUG = booleanPreferencesKey("enable_wine_debug")
    var enableWineDebug: Boolean
        get() = getPref(ENABLE_WINE_DEBUG, false)
        set(value) = setPref(ENABLE_WINE_DEBUG, value)

    // Add new setting for wine debug channels
    private val WINE_DEBUG_CHANNELS = stringPreferencesKey("wine_debug_channels")
    var wineDebugChannels: String
        get() = getPref(WINE_DEBUG_CHANNELS, Constants.XServer.DEFAULT_WINE_DEBUG_CHANNELS)
        set(value) = setPref(WINE_DEBUG_CHANNELS, value)

    // App and notification icon variants
    private val USE_ALT_LAUNCHER_ICON = booleanPreferencesKey("use_alt_launcher_icon")
    var useAltLauncherIcon: Boolean
        get() = getPref(USE_ALT_LAUNCHER_ICON, false)
        set(value) = setPref(USE_ALT_LAUNCHER_ICON, value)

    private val USE_ALT_NOTIFICATION_ICON = booleanPreferencesKey("use_alt_notification_icon")
    var useAltNotificationIcon: Boolean
        get() = getPref(USE_ALT_NOTIFICATION_ICON, false)
        set(value) = setPref(USE_ALT_NOTIFICATION_ICON, value)

    // App language preference (empty string means system default)
    private val APP_LANGUAGE = stringPreferencesKey("app_language")
    var appLanguage: String
        get() = getPref(APP_LANGUAGE, "")
        set(value) = setPref(APP_LANGUAGE, value)

    // Game compatibility cache (JSON string)
    private val GAME_COMPATIBILITY_CACHE = stringPreferencesKey("game_compatibility_cache")
    var gameCompatibilityCache: String
        get() = getPref(GAME_COMPATIBILITY_CACHE, "{}")
        set(value) {
            setPref(GAME_COMPATIBILITY_CACHE, value)
        }
}
