package app.gamenative.ui.component.dialog

import android.widget.Toast
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.tooling.preview.Preview
import app.gamenative.R
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.ui.component.settings.SettingsCPUList
import app.gamenative.ui.component.settings.SettingsCenteredLabel
import app.gamenative.ui.component.settings.SettingsEnvVars
import app.gamenative.ui.component.settings.SettingsListDropdown
import app.gamenative.ui.component.settings.SettingsMultiListDropdown
import app.gamenative.ui.components.rememberCustomGameFolderPicker
import app.gamenative.ui.components.requestPermissionsForPath
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.theme.settingsTileColorsAlt
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.ManifestComponentHelper
import app.gamenative.utils.ManifestContentTypes
import app.gamenative.utils.ManifestData
import app.gamenative.utils.ManifestEntry
import app.gamenative.utils.ManifestInstaller
import app.gamenative.service.SteamService
import app.gamenative.utils.ManifestComponentHelper.VersionOptionList
import app.gamenative.utils.ManifestRepository
import com.winlator.contents.ContentProfile
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.alorma.compose.settings.ui.SettingsSwitch
import com.winlator.box86_64.Box86_64PresetManager
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.core.KeyValueSet
import com.winlator.core.StringUtils
import com.winlator.core.envvars.EnvVarInfo
import com.winlator.core.envvars.EnvVars
import com.winlator.core.envvars.EnvVarSelectionType
import com.winlator.core.DefaultVersion
import com.winlator.core.GPUHelper
import com.winlator.core.WineInfo
import com.winlator.core.WineInfo.MAIN_WINE_VERSION
import com.winlator.fexcore.FEXCoreManager
import com.winlator.fexcore.FEXCorePresetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Gets the component title for Win Components settings group.
 */
private fun winComponentsItemTitleRes(string: String): Int {
    return when (string) {
        "direct3d" -> R.string.direct3d
        "directsound" -> R.string.directsound
        "directmusic" -> R.string.directmusic
        "directplay" -> R.string.directplay
        "directshow" -> R.string.directshow
        "directx" -> R.string.directx
        "vcrun2010" -> R.string.vcrun2010
        "wmdecoder" -> R.string.wmdecoder
        "opengl" -> R.string.wmdecoder
        else -> throw IllegalArgumentException("No string res found for Win Components title: $string")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerConfigDialog(
    visible: Boolean = true,
    default: Boolean = false,
    title: String,
    initialConfig: ContainerData = ContainerData(),
    onDismissRequest: () -> Unit,
    onSave: (ContainerData) -> Unit,
) {
    if (visible) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val installScope = remember {
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        }
        DisposableEffect(Unit) {
            onDispose {
                installScope.cancel()
            }
        }

        var config by rememberSaveable(stateSaver = ContainerData.Saver) {
            mutableStateOf(initialConfig)
        }

        val screenSizes = stringArrayResource(R.array.screen_size_entries).toList()
        val baseGraphicsDrivers = stringArrayResource(R.array.graphics_driver_entries).toList()
        var graphicsDrivers by remember { mutableStateOf(baseGraphicsDrivers.toMutableList()) }
        val dxWrappers = stringArrayResource(R.array.dxwrapper_entries).toList()
        // Start with defaults from resources
        val dxvkVersionsBase = stringArrayResource(R.array.dxvk_version_entries).toList()
        val vkd3dVersionsBase = stringArrayResource(R.array.vkd3d_version_entries).toList()
        val audioDrivers = stringArrayResource(R.array.audio_driver_entries).toList()
        val gpuCards = ContainerUtils.getGPUCards(context)
        val presentModes = stringArrayResource(R.array.present_mode_entries).toList()
        val resourceTypes = stringArrayResource(R.array.resource_type_entries).toList()
        val bcnEmulationEntries = stringArrayResource(R.array.bcn_emulation_entries).toList()
        val bcnEmulationTypeEntries = stringArrayResource(R.array.bcn_emulation_type_entries).toList()
        val sharpnessEffects = stringArrayResource(R.array.vkbasalt_sharpness_entries).toList()
        val sharpnessEffectLabels = stringArrayResource(R.array.vkbasalt_sharpness_labels).toList()
        val sharpnessDisplayItems =
            if (sharpnessEffectLabels.size == sharpnessEffects.size) sharpnessEffectLabels else sharpnessEffects
        val renderingModes = stringArrayResource(R.array.offscreen_rendering_modes).toList()
        val videoMemSizes = stringArrayResource(R.array.video_memory_size_entries).toList()
        val mouseWarps = stringArrayResource(R.array.mouse_warp_override_entries).toList()
        val externalDisplayModes = listOf(
            stringResource(R.string.external_display_mode_off),
            stringResource(R.string.external_display_mode_touchpad),
            stringResource(R.string.external_display_mode_keyboard),
            stringResource(R.string.external_display_mode_hybrid),
        )
        val winCompOpts = stringArrayResource(R.array.win_component_entries).toList()
        val box64Versions = stringArrayResource(R.array.box64_version_entries).toList()
        val wowBox64VersionsBase = stringArrayResource(R.array.wowbox64_version_entries).toList()
        val box64BionicVersionsBase = stringArrayResource(R.array.box64_bionic_version_entries).toList()
        val box64Presets = Box86_64PresetManager.getPresets("box64", context)
        val fexcoreVersionsBase = stringArrayResource(R.array.fexcore_version_entries).toList()
        val fexcorePresets = FEXCorePresetManager.getPresets(context)
        val fexcoreTSOPresets = stringArrayResource(R.array.fexcore_preset_entries).toList()
        val fexcoreX87Presets = stringArrayResource(R.array.x87mode_preset_entries).toList()
        val fexcoreMultiblockValues = stringArrayResource(R.array.multiblock_values).toList()
        val startupSelectionEntries = stringArrayResource(R.array.startup_selection_entries).toList()
        val turnipVersions = stringArrayResource(R.array.turnip_version_entries).toList()
        val virglVersions = stringArrayResource(R.array.virgl_version_entries).toList()
        val zinkVersions = stringArrayResource(R.array.zink_version_entries).toList()
        val vortekVersions = stringArrayResource(R.array.vortek_version_entries).toList()
        val adrenoVersions = stringArrayResource(R.array.adreno_version_entries).toList()
        val sd8EliteVersions = stringArrayResource(R.array.sd8elite_version_entries).toList()
        val containerVariants = stringArrayResource(R.array.container_variant_entries).toList()
        val bionicWineEntriesBase = stringArrayResource(R.array.bionic_wine_entries).toList()
        val glibcWineEntriesBase = stringArrayResource(R.array.glibc_wine_entries).toList()
        var bionicWineEntries by remember { mutableStateOf(bionicWineEntriesBase) }
        var glibcWineEntries by remember { mutableStateOf(glibcWineEntriesBase) }
        val emulatorEntries = stringArrayResource(R.array.emulator_entries).toList()
        val bionicGraphicsDrivers = stringArrayResource(R.array.bionic_graphics_driver_entries).toList()
        val baseWrapperVersions = stringArrayResource(R.array.wrapper_graphics_driver_version_entries).toList()
        var wrapperVersions by remember { mutableStateOf(baseWrapperVersions) }
        var dxvkVersionsAll by remember { mutableStateOf(dxvkVersionsBase) }
        var componentAvailability by remember { mutableStateOf<ManifestComponentHelper.ComponentAvailability?>(null) }
        var manifestInstallInProgress by remember { mutableStateOf(false) }
        var showManifestDownloadDialog by remember { mutableStateOf(false) }
        var manifestDownloadProgress by remember { mutableStateOf(-1f) }
        var manifestDownloadLabel by remember { mutableStateOf("") }
        var versionsLoaded by remember { mutableStateOf(false) }
        var showCustomResolutionDialog by remember { mutableStateOf(false) }
        var customResolutionValidationError by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(visible) {
            if (visible) {
                showCustomResolutionDialog = false
                customResolutionValidationError = null
            }
        }

        val languages = listOf(
            "arabic",
            "bulgarian",
            "schinese",
            "tchinese",
            "czech",
            "danish",
            "dutch",
            "english",
            "finnish",
            "french",
            "german",
            "greek",
            "hungarian",
            "italian",
            "japanese",
            "koreana",
            "norwegian",
            "polish",
            "portuguese",
            "brazilian",
            "romanian",
            "russian",
            "spanish",
            "latam",
            "swedish",
            "thai",
            "turkish",
            "ukrainian",
            "vietnamese",
        )
        val availability = componentAvailability
        val manifestData = availability?.manifest ?: ManifestData.empty()
        val installedLists = availability?.installed

        val isBionicVariant = config.containerVariant.equals(Container.BIONIC, ignoreCase = true)
        val manifestDownloadMessage = if (manifestDownloadLabel.isNotEmpty()) {
            stringResource(R.string.manifest_downloading_item, manifestDownloadLabel)
        } else {
            stringResource(R.string.downloading)
        }

        val manifestDxvk = manifestData.items[ManifestContentTypes.DXVK].orEmpty()
        val manifestVkd3d = manifestData.items[ManifestContentTypes.VKD3D].orEmpty()
        val manifestBox64 = manifestData.items[ManifestContentTypes.BOX64].orEmpty()
        val manifestWowBox64 = manifestData.items[ManifestContentTypes.WOWBOX64].orEmpty()
        val manifestFexcore = manifestData.items[ManifestContentTypes.FEXCORE].orEmpty()
        val manifestDrivers = manifestData.items[ManifestContentTypes.DRIVER].orEmpty()
        val manifestWine = manifestData.items[ManifestContentTypes.WINE].orEmpty()
        val manifestProton = manifestData.items[ManifestContentTypes.PROTON].orEmpty()

        val installedDxvk = installedLists?.dxvk.orEmpty()
        val installedVkd3d = installedLists?.vkd3d.orEmpty()
        val installedBox64 = installedLists?.box64.orEmpty()
        val installedWowBox64 = installedLists?.wowBox64.orEmpty()
        val installedFexcore = installedLists?.fexcore.orEmpty()
        val installedWine = installedLists?.wine.orEmpty()
        val installedProton = installedLists?.proton.orEmpty()
        val installedWrapperDrivers = availability?.installedDrivers.orEmpty()

        val dxvkOptions = remember(dxvkVersionsBase, installedDxvk, manifestDxvk) {
            ManifestComponentHelper.buildVersionOptionList(dxvkVersionsBase, installedDxvk, manifestDxvk)
        }
        val vkd3dOptions = remember(vkd3dVersionsBase, installedVkd3d, manifestVkd3d) {
            ManifestComponentHelper.buildVersionOptionList(vkd3dVersionsBase, installedVkd3d, manifestVkd3d)
        }
        val box64Options = remember(box64Versions, installedBox64, manifestBox64) {
            ManifestComponentHelper.buildVersionOptionList(box64Versions, installedBox64, manifestBox64)
        }
        val box64BionicOptions = remember(box64BionicVersionsBase, installedBox64, manifestBox64) {
            ManifestComponentHelper.buildVersionOptionList(box64BionicVersionsBase, installedBox64, manifestBox64)
        }
        val wowBox64Options = remember(wowBox64VersionsBase, installedWowBox64, manifestWowBox64) {
            ManifestComponentHelper.buildVersionOptionList(wowBox64VersionsBase, installedWowBox64, manifestWowBox64)
        }
        val fexcoreOptions = remember(fexcoreVersionsBase, installedFexcore, manifestFexcore) {
            ManifestComponentHelper.buildVersionOptionList(fexcoreVersionsBase, installedFexcore, manifestFexcore)
        }
        val wrapperOptions = remember(baseWrapperVersions, installedWrapperDrivers, manifestDrivers) {
            ManifestComponentHelper.buildVersionOptionList(baseWrapperVersions, installedWrapperDrivers, manifestDrivers)
        }

        val bionicWineManifest = remember(manifestWine, manifestProton) {
            ManifestComponentHelper.filterManifestByVariant(manifestWine, "bionic") +
                ManifestComponentHelper.filterManifestByVariant(manifestProton, "bionic")
        }
        val glibcWineManifest = remember(manifestWine, manifestProton) {
            ManifestComponentHelper.filterManifestByVariant(manifestWine, "glibc") +
                ManifestComponentHelper.filterManifestByVariant(manifestProton, "glibc")
        }
        val bionicWineOptions = remember(bionicWineEntriesBase, installedWine, installedProton, bionicWineManifest) {
            ManifestComponentHelper.buildVersionOptionList(bionicWineEntriesBase, installedWine + installedProton, bionicWineManifest)
        }
        val glibcWineOptions = remember(glibcWineEntriesBase, glibcWineManifest) {
            ManifestComponentHelper.buildVersionOptionList(glibcWineEntriesBase, emptyList(), glibcWineManifest)
        }

        val dxvkManifestById = remember(manifestDxvk) {
            manifestDxvk.associateBy { StringUtils.parseIdentifier(it.id) }
        }
        val vkd3dManifestById = remember(manifestVkd3d) {
            manifestVkd3d.associateBy { StringUtils.parseIdentifier(it.id) }
        }
        val box64ManifestById = remember(manifestBox64) {
            manifestBox64.associateBy { StringUtils.parseIdentifier(it.id) }
        }
        val wowBox64ManifestById = remember(manifestWowBox64) {
            manifestWowBox64.associateBy { StringUtils.parseIdentifier(it.id) }
        }
        val fexcoreManifestById = remember(manifestFexcore) {
            manifestFexcore.associateBy { StringUtils.parseIdentifier(it.id) }
        }
        val wrapperManifestById = remember(manifestDrivers) {
            manifestDrivers.associateBy { StringUtils.parseIdentifier(it.id) }
        }
        val bionicWineManifestById = remember(bionicWineManifest) {
            bionicWineManifest.associateBy { StringUtils.parseIdentifier(it.id) }
        }
        val glibcWineManifestById = remember(glibcWineManifest) {
            glibcWineManifest.associateBy { StringUtils.parseIdentifier(it.id) }
        }

        suspend fun refreshInstalledLists() {
            val availabilityUpdated = ManifestComponentHelper.loadComponentAvailability(context)
            componentAvailability = availabilityUpdated

            val installed = availabilityUpdated.installed

            wrapperVersions = (baseWrapperVersions + availabilityUpdated.installedDrivers).distinct()
            bionicWineEntries = (bionicWineEntriesBase + installed.proton + installed.wine).distinct()
            glibcWineEntries = glibcWineEntriesBase
        }

        LaunchedEffect(Unit) {
            refreshInstalledLists()
            versionsLoaded = true
        }

        fun launchManifestInstall(
            entry: ManifestEntry,
            label: String,
            isDriver: Boolean,
            expectedType: ContentProfile.ContentType?,
            onInstalled: () -> Unit,
        ) {
            if (manifestInstallInProgress) return
            manifestInstallInProgress = true
            showManifestDownloadDialog = true
            manifestDownloadProgress = -1f
            manifestDownloadLabel = label
            Toast.makeText(
                context,
                context.getString(R.string.manifest_downloading_item, label),
                Toast.LENGTH_SHORT,
            ).show()
            installScope.launch {
                try {
                    val result = ManifestInstaller.installManifestEntry(
                        context = context,
                        entry = entry,
                        isDriver = isDriver,
                        contentType = expectedType,
                        onProgress = { progress ->
                            val clamped = progress.coerceIn(0f, 1f)
                            installScope.launch(Dispatchers.Main.immediate) {
                                manifestDownloadProgress = clamped
                            }
                        },
                    )
                    if (result.success) {
                        refreshInstalledLists()
                        onInstalled()
                    }
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                } finally {
                    manifestInstallInProgress = false
                    showManifestDownloadDialog = false
                    manifestDownloadProgress = -1f
                    manifestDownloadLabel = ""
                }
            }
        }

        fun launchManifestContentInstall(
            entry: ManifestEntry,
            expectedType: ContentProfile.ContentType,
            onInstalled: () -> Unit,
        ) = launchManifestInstall(
            entry = entry,
            label = entry.id,
            isDriver = false,
            expectedType = expectedType,
            onInstalled = onInstalled,
        )

        fun launchManifestDriverInstall(entry: ManifestEntry, onInstalled: () -> Unit) =
            launchManifestInstall(
                entry = entry,
                label = entry.id,
                isDriver = true,
                expectedType = null,
                onInstalled = onInstalled,
            )
        // Vortek/Adreno graphics driver config (vkMaxVersion, imageCacheSize, exposedDeviceExtensions)
        var vkMaxVersionIndex by rememberSaveable { mutableIntStateOf(3) }
        var imageCacheIndex by rememberSaveable { mutableIntStateOf(2) }
        // Exposed device extensions selection indices; populated dynamically when UI opens
        var exposedExtIndices by rememberSaveable { mutableStateOf(listOf<Int>()) }
        val inspectionMode = LocalInspectionMode.current
        val gpuExtensions = remember(inspectionMode) {
            if (inspectionMode) {
                listOf(
                    "VK_KHR_swapchain",
                    "VK_KHR_maintenance1",
                    "VK_KHR_timeline_semaphore",
                )
            } else {
                GPUHelper.vkGetDeviceExtensions().toList()
            }
        }
        LaunchedEffect(config.graphicsDriverConfig) {
            val cfg = KeyValueSet(config.graphicsDriverConfig)
            // Sync Vulkan version index from config
            run {
                val options = listOf("1.0", "1.1", "1.2", "1.3")
                val current = cfg.get("vkMaxVersion", "1.3")
                vkMaxVersionIndex = options.indexOf(current).takeIf { it >= 0 } ?: 3
            }
            // Sync Image cache index from config
            run {
                val options = listOf("64", "128", "256", "512", "1024")
                val current = cfg.get("imageCacheSize", "256")
                imageCacheIndex = options.indexOf(current).let { if (it >= 0) it else 2 }
            }
            val valStr = cfg.get("exposedDeviceExtensions", "all")
            exposedExtIndices = if (valStr == "all" || valStr.isEmpty()) {
                gpuExtensions.indices.toList()
            } else {
                valStr.split("|").mapNotNull { ext -> gpuExtensions.indexOf(ext).takeIf { it >= 0 } }
            }
        }

        // Emulator selections (shown for bionic variant): 64-bit and 32-bit
        var emulator64Index by rememberSaveable {
            // Default based on wine arch: x86_64 -> Box64 (index 1); arm64ec -> FEXCore (index 0)
            val idx = when {
                config.wineVersion.contains("x86_64", true) -> 1
                config.wineVersion.contains("arm64ec", true) -> 0
                else -> 0
            }
            mutableIntStateOf(idx)
        }
        var emulator32Index by rememberSaveable {
            val current = config.emulator.ifEmpty { Container.DEFAULT_EMULATOR }
            val idx = emulatorEntries.indexOfFirst { it.equals(current, true) }.coerceAtLeast(0)
            mutableIntStateOf(idx)
        }

        // Keep emulator defaults in sync when wineVersion changes
        LaunchedEffect(config.wineVersion) {
            if (config.wineVersion.contains("x86_64", true)) {
                emulator64Index = 1 // Box64
                emulator32Index = 1 // Box64
                // lock both later via enabled flags
            } else if (config.wineVersion.contains("arm64ec", true)) {
                emulator64Index = 0 // FEXCore
                if (emulator32Index !in 0..1) emulator32Index = 0
                // Leave 32-bit editable between FEXCore(0) and Box64(1)
            }
        }
        // Max Device Memory (MB) for Vortek/Adreno
        var maxDeviceMemoryIndex by rememberSaveable { mutableIntStateOf(4) } // default 4096
        LaunchedEffect(config.graphicsDriverConfig) {
            val cfg = KeyValueSet(config.graphicsDriverConfig)
            val options = listOf("0", "512", "1024", "2048", "4096")
            val current = cfg.get("maxDeviceMemory", "4096")
            val found = options.indexOf(current)
            maxDeviceMemoryIndex = if (found >= 0) found else 4
        }

        // Bionic-specific state
        var bionicDriverIndex by rememberSaveable {
            val idx = bionicGraphicsDrivers.indexOfFirst { StringUtils.parseIdentifier(it) == config.graphicsDriver }
            mutableIntStateOf(if (idx >= 0) idx else 0)
        }
        var wrapperVersionIndex by rememberSaveable { mutableIntStateOf(0) }
        var presentModeIndex by rememberSaveable { mutableIntStateOf(0) }
        var resourceTypeIndex by rememberSaveable { mutableIntStateOf(0) }
        var bcnEmulationIndex by rememberSaveable { mutableIntStateOf(0) }
        var bcnEmulationTypeIndex by rememberSaveable { mutableIntStateOf(0) }
        var bcnEmulationCacheEnabled by rememberSaveable { mutableStateOf(false) }
        var disablePresentWaitChecked by rememberSaveable { mutableStateOf(false) }
        var syncEveryFrameChecked by rememberSaveable { mutableStateOf(false) }
        var sharpnessEffectIndex by rememberSaveable {
            val idx = sharpnessEffects.indexOfFirst { it.equals(config.sharpnessEffect, true) }.coerceAtLeast(0)
            mutableIntStateOf(idx)
        }
        var sharpnessLevel by rememberSaveable { mutableIntStateOf(config.sharpnessLevel.coerceIn(0, 100)) }
        var sharpnessDenoise by rememberSaveable { mutableIntStateOf(config.sharpnessDenoise.coerceIn(0, 100)) }
        var adrenotoolsTurnipChecked by rememberSaveable {
            val cfg = KeyValueSet(config.graphicsDriverConfig)
            mutableStateOf(cfg.get("adrenotoolsTurnip", "1") != "0")
        }
        LaunchedEffect(config.graphicsDriverConfig) {
            val cfg = KeyValueSet(config.graphicsDriverConfig)
            val presentMode = cfg.get("presentMode", "mailbox")
            val defaultPresentIdx = presentModes.indexOfFirst { it.equals("mailbox", true) }.takeIf { it >= 0 } ?: 0
            presentModeIndex =
                presentModes.indexOfFirst { it.equals(presentMode, true) }.let { if (it >= 0) it else defaultPresentIdx }

            val resourceType = cfg.get("resourceType", "auto")
            val defaultResourceIdx = resourceTypes.indexOfFirst { it.equals("auto", true) }.takeIf { it >= 0 } ?: 0
            resourceTypeIndex =
                resourceTypes.indexOfFirst { it.equals(resourceType, true) }.let { if (it >= 0) it else defaultResourceIdx }

            val bcnMode = cfg.get("bcnEmulation", "auto")
            val defaultBcnIdx = bcnEmulationEntries.indexOfFirst { it.equals("auto", true) }.takeIf { it >= 0 } ?: 0
            bcnEmulationIndex =
                bcnEmulationEntries.indexOfFirst { it.equals(bcnMode, true) }.let { if (it >= 0) it else defaultBcnIdx }

            val bcnType = cfg.get("bcnEmulationType", bcnEmulationTypeEntries.firstOrNull().orEmpty())
            val defaultBcnTypeIdx = bcnEmulationTypeEntries.indexOfFirst { it.equals(bcnType, true) }.takeIf { it >= 0 } ?: 0
            bcnEmulationTypeIndex = defaultBcnTypeIdx

            bcnEmulationCacheEnabled = cfg.get("bcnEmulationCache", "0") == "1"
            disablePresentWaitChecked = cfg.get("disablePresentWait", "0") == "1"

            val syncRaw = cfg.get("syncFrame").ifEmpty { cfg.get("frameSync", "0") }
            syncEveryFrameChecked = syncRaw == "1" || syncRaw.equals("Always", true)

            adrenotoolsTurnipChecked = cfg.get("adrenotoolsTurnip", "1") != "0"
        }

        LaunchedEffect(config.sharpnessEffect, config.sharpnessLevel, config.sharpnessDenoise) {
            sharpnessEffectIndex = sharpnessEffects.indexOfFirst { it.equals(config.sharpnessEffect, true) }.coerceAtLeast(0)
            sharpnessLevel = config.sharpnessLevel.coerceIn(0, 100)
            sharpnessDenoise = config.sharpnessDenoise.coerceIn(0, 100)
        }

        LaunchedEffect(versionsLoaded, wrapperOptions, config.graphicsDriverConfig) {
            if (!versionsLoaded) return@LaunchedEffect
            val cfg = KeyValueSet(config.graphicsDriverConfig)
            val ver = cfg.get("version", DefaultVersion.WRAPPER)
            val newIdx = wrapperOptions.ids.indexOfFirst { it.equals(ver, true) }.coerceAtLeast(0)
            if (wrapperVersionIndex != newIdx) wrapperVersionIndex = newIdx
        }

        var screenSizeIndex by rememberSaveable {
            val searchIndex = screenSizes.indexOfFirst { it.contains(config.screenSize) }
            mutableIntStateOf(if (searchIndex > 0) searchIndex else 0)
        }
        var customScreenWidth by rememberSaveable {
            val searchIndex = screenSizes.indexOfFirst { it.contains(config.screenSize) }
            mutableStateOf(
                if (searchIndex <= 0) {
                    config.screenSize.split("x").getOrElse(0) { "1280" }
                } else "1280"
            )
        }
        var customScreenHeight by rememberSaveable {
            val searchIndex = screenSizes.indexOfFirst { it.contains(config.screenSize) }
            mutableStateOf(
                if (searchIndex <= 0) {
                    config.screenSize.split("x").getOrElse(1) { "720" }
                } else "720"
            )
        }
        var graphicsDriverIndex by rememberSaveable {
            val driverIndex = graphicsDrivers.indexOfFirst { StringUtils.parseIdentifier(it) == config.graphicsDriver }
            mutableIntStateOf(if (driverIndex >= 0) driverIndex else 0)
        }

        // Function to get the appropriate version list based on the selected graphics driver
        fun getVersionsForDriver(): List<String> {
            val driverType = StringUtils.parseIdentifier(graphicsDrivers[graphicsDriverIndex])
            return when (driverType) {
                "turnip" -> turnipVersions
                "virgl" -> virglVersions
                "vortek" -> vortekVersions
                "adreno" -> adrenoVersions
                "sd-8-elite" -> sd8EliteVersions
                else -> zinkVersions
            }
        }

        fun getVersionsForBox64(): VersionOptionList {
            return if (config.containerVariant.equals(Container.GLIBC, ignoreCase = true)) {
                box64Options
            } else if (config.wineVersion.contains("x86_64", true)) {
                box64BionicOptions
            } else if (config.wineVersion.contains("arm64ec", true)) {
                wowBox64Options
            } else {
                box64Options
            }
        }

        fun getStartupSelectionOptions(): List<String> {
            if (config.containerVariant.equals(Container.GLIBC)) {
                return startupSelectionEntries
            } else {
                return startupSelectionEntries.subList(0, 2)
            }
        }
        var dxWrapperIndex by rememberSaveable {
            val driverIndex = dxWrappers.indexOfFirst { StringUtils.parseIdentifier(it) == config.dxwrapper }
            mutableIntStateOf(if (driverIndex >= 0) driverIndex else 0)
        }

        var dxvkVersionIndex by rememberSaveable { mutableIntStateOf(0) }

        // VKD3D version control (forced depending on driver)
        fun vkd3dForcedVersion(): String {
            val driverType = StringUtils.parseIdentifier(graphicsDrivers[graphicsDriverIndex])
            val isVortekLike = config.containerVariant.equals(Container.GLIBC) && driverType == "vortek" || driverType == "adreno" || driverType == "sd-8-elite"
            return if (isVortekLike) "2.6" else "2.14.1"
        }

        @Composable
        fun DxWrapperSection() {
            // TODO: add way to pick DXVK version
            SettingsListDropdown(
                colors = settingsTileColors(),
                title = { Text(text = stringResource(R.string.dx_wrapper)) },
                value = dxWrapperIndex,
                items = dxWrappers,
                onItemSelected = {
                    dxWrapperIndex = it
                    config = config.copy(dxwrapper = StringUtils.parseIdentifier(dxWrappers[it]))
                },
            )
            // DXVK Version Dropdown (conditionally visible and constrained)
            run {
                val driverType = StringUtils.parseIdentifier(graphicsDrivers[graphicsDriverIndex])
                val isVortekLike = config.containerVariant.equals(Container.GLIBC) && driverType == "vortek" || driverType == "adreno" || driverType == "sd-8-elite"
                val isVKD3D = StringUtils.parseIdentifier(dxWrappers[dxWrapperIndex]) == "vkd3d"
                val constrainedLabels = listOf("1.10.3", "1.10.9-sarek", "1.9.2", "async-1.10.3")
                val constrainedIds = constrainedLabels.map { StringUtils.parseIdentifier(it) }
                val useConstrained =
                    !inspectionMode && isVortekLike && GPUHelper.vkGetApiVersionSafe() < GPUHelper.vkMakeVersion(
                        1,
                        3,
                        0
                    )
                val items =
                    if (useConstrained) constrainedLabels
                    else if (isBionicVariant) dxvkOptions.labels
                    else dxvkVersionsBase
                val itemIds =
                    if (useConstrained) constrainedIds
                    else if (isBionicVariant) dxvkOptions.ids
                    else dxvkVersionsBase.map { StringUtils.parseIdentifier(it) }
                val itemMuted =
                    if (useConstrained) List(items.size) { false }
                    else if (isBionicVariant) dxvkOptions.muted
                    else null
                if (!isVKD3D) {
                    SettingsListDropdown(
                        colors = settingsTileColors(),
                        title = { Text(text = stringResource(R.string.dxvk_version)) },
                        value = dxvkVersionIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                        items = items,
                        itemMuted = itemMuted,
                        onItemSelected = {
                            dxvkVersionIndex = it
                            val selectedId = itemIds.getOrNull(it).orEmpty()
                            val isManifestNotInstalled = isBionicVariant && itemMuted?.getOrNull(it) == true
                            val manifestEntry = if (isBionicVariant) dxvkManifestById[selectedId] else null
                            if (isManifestNotInstalled && manifestEntry != null) {
                                launchManifestContentInstall(
                                    manifestEntry,
                                    ContentProfile.ContentType.CONTENT_TYPE_DXVK,
                                ) {
                                    val currentConfig = KeyValueSet(config.dxwrapperConfig)
                                    currentConfig.put("version", selectedId)
                                    if (selectedId.contains("async", ignoreCase = true)) currentConfig.put("async", "1")
                                    else currentConfig.put("async", "0")
                                    if (selectedId.contains("gplasync", ignoreCase = true)) currentConfig.put("asyncCache", "1")
                                    else currentConfig.put("asyncCache", "0")
                                    config = config.copy(dxwrapperConfig = currentConfig.toString())
                                }
                                return@SettingsListDropdown
                            }
                            val version = selectedId.ifEmpty { StringUtils.parseIdentifier(items[it]) }
                            val currentConfig = KeyValueSet(config.dxwrapperConfig)
                            currentConfig.put("version", version)
                            val envVarsSet = EnvVars(config.envVars)
                            if (version.contains("async", ignoreCase = true)) currentConfig.put("async", "1")
                            else currentConfig.put("async", "0")
                            if (version.contains("gplasync", ignoreCase = true)) currentConfig.put("asyncCache", "1")
                            else currentConfig.put("asyncCache", "0")
                            config =
                                config.copy(dxwrapperConfig = currentConfig.toString(), envVars = envVarsSet.toString())
                        },
                    )
                } else {
                    // Ensure default version for vortek-like when hidden
                    val version = if (isVortekLike) "1.10.3" else "2.4.1"
                    val currentConfig = KeyValueSet(config.dxwrapperConfig)
                    currentConfig.put("version", version)
                    config = config.copy(dxwrapperConfig = currentConfig.toString())
                }
            }
            // VKD3D Version UI (visible only when VKD3D selected)
            run {
                val isVKD3D = StringUtils.parseIdentifier(dxWrappers[dxWrapperIndex]) == "vkd3d"
                if (isVKD3D) {
                    val label = "VKD3D Version"
                    val availableVersions = if (isBionicVariant) vkd3dOptions.labels else vkd3dVersionsBase
                    val availableIds = if (isBionicVariant) vkd3dOptions.ids else vkd3dVersionsBase
                    val availableMuted = if (isBionicVariant) vkd3dOptions.muted else null
                    val selectedVersion =
                        KeyValueSet(config.dxwrapperConfig).get("vkd3dVersion").ifEmpty { vkd3dForcedVersion() }
                    val selectedIndex = availableIds.indexOf(selectedVersion).coerceAtLeast(0)

                    SettingsListDropdown(
                        colors = settingsTileColors(),
                        title = { Text(text = label) },
                        value = selectedIndex,
                        items = availableVersions,
                        itemMuted = availableMuted,
                        onItemSelected = { idx ->
                            val selectedId = availableIds.getOrNull(idx).orEmpty()
                            val isManifestNotInstalled = isBionicVariant && availableMuted?.getOrNull(idx) == true
                            val manifestEntry = if (isBionicVariant) vkd3dManifestById[selectedId] else null
                            if (isManifestNotInstalled && manifestEntry != null) {
                                launchManifestContentInstall(
                                    manifestEntry,
                                    ContentProfile.ContentType.CONTENT_TYPE_VKD3D,
                                ) {
                                    val currentConfig = KeyValueSet(config.dxwrapperConfig)
                                    currentConfig.put("vkd3dVersion", selectedId)
                                    config = config.copy(dxwrapperConfig = currentConfig.toString())
                                }
                                return@SettingsListDropdown
                            }
                            val currentConfig = KeyValueSet(config.dxwrapperConfig)
                            currentConfig.put("vkd3dVersion", selectedId.ifEmpty { availableVersions[idx] })
                            config = config.copy(dxwrapperConfig = currentConfig.toString())
                        },
                    )

                    // VKD3D Feature Level selector
                    val featureLevels = listOf("12_2", "12_1", "12_0", "11_1", "11_0")
                    val cfg = KeyValueSet(config.dxwrapperConfig)
                    val currentLevel = cfg.get("vkd3dFeatureLevel", "12_1")
                    val currentLevelIndex = featureLevels.indexOf(currentLevel).coerceAtLeast(0)
                    SettingsListDropdown(
                        colors = settingsTileColors(),
                        title = { Text(text = stringResource(R.string.vkd3d_feature_level)) },
                        value = currentLevelIndex,
                        items = featureLevels,
                        onItemSelected = {
                            val selected = featureLevels[it]
                            val currentConfig = KeyValueSet(config.dxwrapperConfig)
                            currentConfig.put("vkd3dFeatureLevel", selected)
                            config = config.copy(dxwrapperConfig = currentConfig.toString())
                        },
                    )
                }
            }
        }

        var graphicsDriverVersionIndex by rememberSaveable {
            // Find the version in the list that matches the configured version
            val version = config.graphicsDriverVersion
            val driverIndex = if (version.isEmpty()) {
                0 // Default
            } else {
                // Try to find the version in the list
                val index = getVersionsForDriver().indexOfFirst { it == version }
                if (index >= 0) index else 0
            }
            mutableIntStateOf(driverIndex)
        }
        fun currentDxvkContext(): ManifestComponentHelper.DxvkContext =
            ManifestComponentHelper.buildDxvkContext(
                containerVariant = config.containerVariant,
                graphicsDrivers = graphicsDrivers,
                graphicsDriverIndex = graphicsDriverIndex,
                dxWrappers = dxWrappers,
                dxWrapperIndex = dxWrapperIndex,
                inspectionMode = inspectionMode,
                isBionicVariant = isBionicVariant,
                dxvkVersionsBase = dxvkVersionsBase,
                dxvkOptions = dxvkOptions,
            )
        // Keep dxwrapperConfig in sync when VKD3D selected
        LaunchedEffect(graphicsDriverIndex, dxWrapperIndex) {
            val isVKD3D = StringUtils.parseIdentifier(dxWrappers[dxWrapperIndex]) == "vkd3d"
            if (isVKD3D) {
                val kvs = KeyValueSet(config.dxwrapperConfig)
                if (kvs.get("vkd3dVersion").isEmpty()) {
                    kvs.put("vkd3dVersion", vkd3dForcedVersion())
                }
                // Ensure a default VKD3D feature level is set
                if (kvs.get("vkd3dFeatureLevel").isEmpty()) {
                    kvs.put("vkd3dFeatureLevel", "12_1")
                }
                config = config.copy(dxwrapperConfig = kvs.toString())
            }
        }

        LaunchedEffect(versionsLoaded, dxvkOptions, dxvkVersionsBase, graphicsDriverIndex, dxWrapperIndex, config.dxwrapperConfig) {
            if (!versionsLoaded) return@LaunchedEffect
            val kvs = KeyValueSet(config.dxwrapperConfig)
            val configuredVersion = kvs.get("version")
            if (configuredVersion.isEmpty()) return@LaunchedEffect
            val context = currentDxvkContext()
            if (context.ids.isEmpty()) return@LaunchedEffect
            val normalizedConfiguredVersion = StringUtils.parseIdentifier(configuredVersion)
            val foundIndex = context.ids.indexOfFirst {
                it == configuredVersion || StringUtils.parseIdentifier(it) == normalizedConfiguredVersion
            }
            val defaultIndex = context.ids.indexOfFirst {
                it == DefaultVersion.DXVK || StringUtils.parseIdentifier(it) == StringUtils.parseIdentifier(DefaultVersion.DXVK)
            }.coerceAtLeast(0)
            val newIdx = if (foundIndex >= 0) foundIndex else defaultIndex
            if (dxvkVersionIndex != newIdx) dxvkVersionIndex = newIdx
        }
        // When DXVK version defaults to an 'async' build, enable DXVK_ASYNC by default
        LaunchedEffect(versionsLoaded, dxvkVersionIndex, graphicsDriverIndex, dxWrapperIndex) {
            if (!versionsLoaded) return@LaunchedEffect
            val context = currentDxvkContext()
            if (context.ids.isEmpty()) return@LaunchedEffect
            if (dxvkVersionIndex !in context.ids.indices) dxvkVersionIndex = 0

            // Ensure index within range or default
            val selectedVersion = context.ids.getOrNull(dxvkVersionIndex).orEmpty()
            val version = if (selectedVersion.isEmpty()) {
                if (context.isVortekLike) "async-1.10.3" else DefaultVersion.DXVK
            } else selectedVersion
            val envSet = EnvVars(config.envVars)
            // Update dxwrapperConfig version only when DXVK wrapper selected
            val wrapperIsDxvk = StringUtils.parseIdentifier(dxWrappers[dxWrapperIndex]) == "dxvk"
            val kvs = KeyValueSet(config.dxwrapperConfig)
            val currentVersion = kvs.get("version")
            // Only update if the version actually changed (don't overwrite on initial load if it matches)
            if (wrapperIsDxvk) {
                // Check if we need to update - only if current version doesn't match selected version
                val needsUpdate = currentVersion.isEmpty() ||
                    (currentVersion != version && StringUtils.parseIdentifier(currentVersion) != StringUtils.parseIdentifier(version))
                if (needsUpdate) {
                    kvs.put("version", version)
                }
            }
            if (version.contains("async", ignoreCase = true)) {
                kvs.put("async", "1")
            } else {
                kvs.put("async", "0")
            }
            if (version.contains("gplasync", ignoreCase = true)) {
                kvs.put("asyncCache", "1")
            } else {
                kvs.put("asyncCache", "0")
            }
            config = config.copy(envVars = envSet.toString(), dxwrapperConfig = kvs.toString())
        }
        var audioDriverIndex by rememberSaveable {
            val driverIndex = audioDrivers.indexOfFirst { StringUtils.parseIdentifier(it) == config.audioDriver }
            mutableIntStateOf(if (driverIndex >= 0) driverIndex else 0)
        }
        var gpuNameIndex by rememberSaveable {
            val gpuInfoIndex = gpuCards.values.indexOfFirst { it.deviceId == config.videoPciDeviceID }
            mutableIntStateOf(if (gpuInfoIndex >= 0) gpuInfoIndex else 0)
        }
        var renderingModeIndex by rememberSaveable {
            val index = renderingModes.indexOfFirst { it.lowercase() == config.offScreenRenderingMode }
            mutableIntStateOf(if (index >= 0) index else 0)
        }
        var videoMemIndex by rememberSaveable {
            val index = videoMemSizes.indexOfFirst { StringUtils.parseNumber(it) == config.videoMemorySize }
            mutableIntStateOf(if (index >= 0) index else 0)
        }
        var mouseWarpIndex by rememberSaveable {
            val index = mouseWarps.indexOfFirst { it.lowercase() == config.mouseWarpOverride }
            mutableIntStateOf(if (index >= 0) index else 0)
        }
        var externalDisplayModeIndex by rememberSaveable {
            val index = when (config.externalDisplayMode.lowercase()) {
                Container.EXTERNAL_DISPLAY_MODE_TOUCHPAD -> 1
                Container.EXTERNAL_DISPLAY_MODE_KEYBOARD -> 2
                Container.EXTERNAL_DISPLAY_MODE_HYBRID -> 3
                else -> 0
            }
            mutableIntStateOf(index)
        }
        var languageIndex by rememberSaveable {
            val idx = languages.indexOfFirst { it == config.language.lowercase() }
            mutableIntStateOf(if (idx >= 0) idx else languages.indexOf("english"))
        }

        var dismissDialogState by rememberSaveable(stateSaver = MessageDialogState.Saver) {
            mutableStateOf(MessageDialogState(visible = false))
        }
        var showEnvVarCreateDialog by rememberSaveable { mutableStateOf(false) }
        var showAddDriveDialog by rememberSaveable { mutableStateOf(false) }
        var selectedDriveLetter by rememberSaveable { mutableStateOf("") }
        var pendingDriveLetter by rememberSaveable { mutableStateOf("") }
        var driveLetterMenuExpanded by rememberSaveable { mutableStateOf(false) }

        val reservedDriveLetters = setOf("C", "Z")
        val nonDeletableDriveLetters = setOf("A", "C", "D", "Z")
        val availableDriveLetters = remember(config.drives) {
            val usedDriveLetters = Container.drivesIterator(config.drives)
                .map { it[0].uppercase(Locale.ENGLISH) }
                .toSet()
            ('A'..'Z').map { it.toString() }
                .filter { it !in usedDriveLetters && it !in reservedDriveLetters }
        }

        val storagePermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { }

        val folderPicker = rememberCustomGameFolderPicker(
            onPathSelected = { path ->
                SteamService.keepAlive = false
                val letter = pendingDriveLetter.uppercase(Locale.ENGLISH)
                if (letter.isBlank()) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.container_config_drive_letter_missing),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@rememberCustomGameFolderPicker
                }
                if (!availableDriveLetters.contains(letter)) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.no_available_drive_letters),
                        Toast.LENGTH_SHORT,
                    ).show()
                    pendingDriveLetter = ""
                    return@rememberCustomGameFolderPicker
                }
                if (path.isBlank() || path.contains(":")) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.container_config_invalid_drive_path),
                        Toast.LENGTH_SHORT,
                    ).show()
                    pendingDriveLetter = ""
                    return@rememberCustomGameFolderPicker
                }

                val folder = File(path)
                val canAccess = try {
                    folder.exists() && folder.isDirectory && folder.canRead()
                } catch (_: Exception) {
                    false
                }
                if (!canAccess && !CustomGameScanner.hasStoragePermission(context, path)) {
                    requestPermissionsForPath(context, path, storagePermissionLauncher)
                }

                config = config.copy(drives = "${config.drives}${letter}:${path}")
                pendingDriveLetter = ""
            },
            onFailure = { message ->
                SteamService.keepAlive = false
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            },
            onCancel = {
                SteamService.keepAlive = false
            },
        )

        val applyScreenSizeToConfig: () -> Unit = {
            val screenSize = if (screenSizeIndex == 0) {
                if (customScreenWidth.isNotEmpty() && customScreenHeight.isNotEmpty()) {
                    "${customScreenWidth}x$customScreenHeight"
                } else {
                    config.screenSize
                }
            } else {
                screenSizes[screenSizeIndex].split(" ")[0]
            }
            config = config.copy(screenSize = screenSize)
        }

        val onDismissCheck: () -> Unit = {
            if (initialConfig != config) {
                dismissDialogState = MessageDialogState(
                    visible = true,
                    title = context.getString(R.string.container_config_unsaved_changes_title),
                    message = context.getString(R.string.container_config_unsaved_changes_message),
                    confirmBtnText = context.getString(R.string.discard),
                    dismissBtnText = context.getString(R.string.cancel),
                )
            } else {
                onDismissRequest()
            }
        }

        val nonzeroResolutionError = stringResource(
            R.string.container_config_custom_resolution_error_nonzero
        )
        val aspectResolutionError = stringResource(
            R.string.container_config_custom_resolution_error_aspect
        )
        LoadingDialog(
            visible = showManifestDownloadDialog,
            progress = manifestDownloadProgress,
            message = manifestDownloadMessage,
        )
        if (showCustomResolutionDialog) {
            AlertDialog(
                onDismissRequest = { showCustomResolutionDialog = false },
                title = { Text(text = stringResource(R.string.container_config_custom_resolution_title)) },
                text = {
                    Column {
                        Row {
                            OutlinedTextField(
                                modifier = Modifier.width(128.dp),
                                value = customScreenWidth,
                                onValueChange = {
                                    customScreenWidth = it
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                label = { Text(text = stringResource(R.string.width)) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            modifier = Modifier.align(Alignment.CenterVertically),
                            text = stringResource(R.string.container_config_custom_resolution_separator),
                            style = TextStyle(fontSize = 16.sp),
                        )
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedTextField(
                                modifier = Modifier.width(128.dp),
                                value = customScreenHeight,
                                onValueChange = {
                                    customScreenHeight = it
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                label = { Text(text = stringResource(R.string.height)) },
                            )
                        }
                        if (customResolutionValidationError != null) {
                            Text(
                                text = customResolutionValidationError!!,
                                color = MaterialTheme.colorScheme.error,
                                style = TextStyle(fontSize = 16.sp),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val widthInt = customScreenWidth.toIntOrNull() ?: 0
                            val heightInt = customScreenHeight.toIntOrNull() ?: 0
                            if (widthInt == 0 || heightInt == 0) {
                                customResolutionValidationError = nonzeroResolutionError
                            } else if (widthInt <= heightInt) {
                                customResolutionValidationError = aspectResolutionError
                            } else {
                                customResolutionValidationError = null
                                applyScreenSizeToConfig()
                                showCustomResolutionDialog = false
                            }
                        },
                    ) {
                        Text(text = stringResource(R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showCustomResolutionDialog = false
                        },
                    ) {
                        Text(text = stringResource(R.string.cancel))
                    }
                }
            )
        }

        MessageDialog(
            visible = dismissDialogState.visible,
            title = dismissDialogState.title,
            message = dismissDialogState.message,
            confirmBtnText = dismissDialogState.confirmBtnText,
            dismissBtnText = dismissDialogState.dismissBtnText,
            onDismissRequest = { dismissDialogState = MessageDialogState(visible = false) },
            onDismissClick = { dismissDialogState = MessageDialogState(visible = false) },
            onConfirmClick = onDismissRequest,
        )

        if (showEnvVarCreateDialog) {
            var envVarName by rememberSaveable { mutableStateOf("") }
            var envVarValue by rememberSaveable { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showEnvVarCreateDialog = false },
                title = { Text(text = stringResource(R.string.new_environment_variable)) },
                text = {
                    var knownVarsMenuOpen by rememberSaveable { mutableStateOf(false) }
                    Column {
                        Row {
                            OutlinedTextField(
                                value = envVarName,
                                onValueChange = { envVarName = it },
                                label = { Text(text = stringResource(R.string.name)) },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { knownVarsMenuOpen = true },
                                        content = {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Outlined.ViewList,
                                                contentDescription = "List known variable names",
                                            )
                                        },
                                    )
                                },
                            )
                            DropdownMenu(
                                expanded = knownVarsMenuOpen,
                                onDismissRequest = { knownVarsMenuOpen = false },
                            ) {
                                val knownEnvVars = EnvVarInfo.KNOWN_ENV_VARS.values.filter {
                                    !config.envVars.contains("${it.identifier}=")
                                }
                                if (knownEnvVars.isNotEmpty()) {
                                    for (knownVariable in knownEnvVars) {
                                        DropdownMenuItem(
                                            text = { Text(knownVariable.identifier) },
                                            onClick = {
                                                envVarName = knownVariable.identifier
                                                knownVarsMenuOpen = false
                                            },
                                        )
                                    }
                                } else {
                                    DropdownMenuItem(
                                        text = { Text(text = stringResource(R.string.no_more_known_variables)) },
                                        onClick = {},
                                    )
                                }
                            }
                        }
                        val selectedEnvVarInfo = EnvVarInfo.KNOWN_ENV_VARS[envVarName]
                        if (selectedEnvVarInfo?.selectionType == EnvVarSelectionType.MULTI_SELECT) {
                            var multiSelectedIndices by remember { mutableStateOf(listOf<Int>()) }
                            SettingsMultiListDropdown(
                                enabled = true,
                                values = multiSelectedIndices,
                                items = selectedEnvVarInfo.possibleValues,
                                fallbackDisplay = "",
                                onItemSelected = { index ->
                                    val newIndices = if (multiSelectedIndices.contains(index)) {
                                        multiSelectedIndices.filter { it != index }
                                    } else {
                                        multiSelectedIndices + index
                                    }
                                    multiSelectedIndices = newIndices
                                    envVarValue = newIndices.joinToString(",") { selectedEnvVarInfo.possibleValues[it] }
                                },
                                title = { Text(text = stringResource(R.string.value)) },
                                colors = settingsTileColors(),
                            )
                        } else {
                            OutlinedTextField(
                                value = envVarValue,
                                onValueChange = { envVarValue = it },
                                label = { Text(text = stringResource(R.string.value)) },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showEnvVarCreateDialog = false },
                        content = { Text(text = stringResource(R.string.cancel)) },
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = envVarName.isNotEmpty(),
                        onClick = {
                            val envVars = EnvVars(config.envVars)
                            envVars.put(envVarName, envVarValue)
                            config = config.copy(envVars = envVars.toString())
                            showEnvVarCreateDialog = false
                        },
                        content = { Text(text = stringResource(R.string.ok)) },
                    )
                },
            )
        }

        if (showAddDriveDialog) {
            AlertDialog(
                onDismissRequest = { showAddDriveDialog = false },
                title = { Text(text = stringResource(R.string.add_drive)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = selectedDriveLetter,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(text = stringResource(R.string.drive_letter)) },
                            trailingIcon = {
                                IconButton(
                                    onClick = { driveLetterMenuExpanded = true },
                                    content = {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Outlined.ViewList,
                                            contentDescription = null,
                                        )
                                    },
                                )
                            },
                        )
                        DropdownMenu(
                            expanded = driveLetterMenuExpanded,
                            onDismissRequest = { driveLetterMenuExpanded = false },
                        ) {
                            availableDriveLetters.forEach { letter ->
                                DropdownMenuItem(
                                    text = { Text(text = letter) },
                                    onClick = {
                                        selectedDriveLetter = letter
                                        driveLetterMenuExpanded = false
                                    },
                                )
                            }
                        }
                        if (availableDriveLetters.isEmpty()) {
                            Text(
                                text = stringResource(R.string.no_available_drive_letters),
                                color = MaterialTheme.colorScheme.error,
                                style = TextStyle(fontSize = 14.sp),
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = selectedDriveLetter.isNotBlank() &&
                            availableDriveLetters.contains(selectedDriveLetter),
                        onClick = {
                            pendingDriveLetter = selectedDriveLetter
                            showAddDriveDialog = false
                            SteamService.keepAlive = true
                            folderPicker.launchPicker()
                        },
                        content = { Text(text = stringResource(R.string.ok)) },
                    )
                },
                dismissButton = {
                    TextButton(
                        onClick = { showAddDriveDialog = false },
                        content = { Text(text = stringResource(R.string.cancel)) },
                    )
                },
            )
        }

        Dialog(
            onDismissRequest = onDismissCheck,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false,
            ),
            content = {
                val scrollState = rememberScrollState()

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = {
                                Text(
                                    text = "$title${if (initialConfig != config) "*" else ""}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            navigationIcon = {
                                IconButton(
                                    onClick = onDismissCheck,
                                    content = { Icon(Icons.Default.Close, null) },
                                )
                            },
                            actions = {
                                IconButton(
                                    onClick = { onSave(config) },
                                    content = { Icon(Icons.Default.Save, null) },
                                )
                            },
                        )
                    },
                ) { paddingValues ->
                    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
                    val tabs = listOf(
                        stringResource(R.string.container_config_tab_general),
                        stringResource(R.string.container_config_tab_graphics),
                        stringResource(R.string.container_config_tab_emulation),
                        stringResource(R.string.container_config_tab_controller),
                        stringResource(R.string.container_config_tab_wine),
                        stringResource(R.string.container_config_tab_win_components),
                        stringResource(R.string.container_config_tab_environment),
                        stringResource(R.string.container_config_tab_drives),
                        stringResource(R.string.container_config_tab_advanced)
                    )
                    Column(
                        modifier = Modifier
                            .padding(
                                top = app.gamenative.utils.PaddingUtils.statusBarAwarePadding().calculateTopPadding() + paddingValues.calculateTopPadding(),
                                bottom = 32.dp + paddingValues.calculateBottomPadding(),
                                start = paddingValues.calculateStartPadding(LayoutDirection.Ltr),
                                end = paddingValues.calculateEndPadding(LayoutDirection.Ltr),
                            )
                            .fillMaxSize(),
                    ) {
                        androidx.compose.material3.ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 0.dp) {
                            tabs.forEachIndexed { index, label ->
                                androidx.compose.material3.Tab(
                                    selected = selectedTab == index,
                                    onClick = { selectedTab = index },
                                    text = { Text(text = label) },
                                )
                            }
                        }
                        Column(
                            modifier = Modifier
                                .verticalScroll(scrollState)
                                .weight(1f),
                        ) {
                            if (selectedTab == 0) SettingsGroup() {
                                // Variant selector (glibc/bionic)
                                run {
                                    val variantIndex = rememberSaveable {
                                        mutableIntStateOf(containerVariants.indexOfFirst { it.equals(config.containerVariant, true) }
                                            .coerceAtLeast(0))
                                    }
                                    SettingsListDropdown(
                                        colors = settingsTileColors(),
                                        title = { Text(text = stringResource(R.string.container_variant)) },
                                        value = variantIndex.value,
                                        items = containerVariants,
                                        onItemSelected = { idx ->
                                            variantIndex.value = idx
                                            val newVariant = containerVariants[idx]
                                            if (newVariant.equals(Container.GLIBC, ignoreCase = true)) {
                                                // Switch to glibc: reset to default graphics driver and clear wrapper-specific version
                                                val defaultDriver = Container.DEFAULT_GRAPHICS_DRIVER
                                                val newCfg = KeyValueSet(config.graphicsDriverConfig).apply {
                                                    put("version", "")
                                                    put("syncFrame", "0")
                                                    put("disablePresentWait", get("disablePresentWait").ifEmpty { "0" })
                                                    if (get("presentMode").isEmpty()) put("presentMode", "mailbox")
                                                    if (get("resourceType").isEmpty()) put("resourceType", "auto")
                                                    if (get("bcnEmulation").isEmpty()) put("bcnEmulation", "auto")
                                                    if (get("bcnEmulationType").isEmpty()) put("bcnEmulationType", "software")
                                                    if (get("bcnEmulationCache").isEmpty()) put("bcnEmulationCache", "0")
                                                    put("adrenotoolsTurnip", "1")
                                                }
                                                graphicsDriverIndex =
                                                    graphicsDrivers.indexOfFirst { StringUtils.parseIdentifier(it) == defaultDriver }
                                                        .coerceAtLeast(0)
                                                graphicsDriverVersionIndex = 0
                                                syncEveryFrameChecked = false
                                                disablePresentWaitChecked = newCfg.get("disablePresentWait", "0") == "1"
                                                bcnEmulationCacheEnabled = newCfg.get("bcnEmulationCache", "0") == "1"
                                                adrenotoolsTurnipChecked = true

                                                val defaultGlibcWine = glibcWineEntries.firstOrNull() ?: Container.DEFAULT_WINE_VERSION
                                                config = config.copy(
                                                    containerVariant = newVariant,
                                                    wineVersion = defaultGlibcWine,
                                                    graphicsDriver = defaultDriver,
                                                    graphicsDriverVersion = "",
                                                    graphicsDriverConfig = newCfg.toString(),
                                                    box64Version = "0.3.6",
                                                )
                                            } else {
                                                // Switch to bionic: set wrapper defaults
                                                val defaultBionicDriver = StringUtils.parseIdentifier(bionicGraphicsDrivers.first())
                                                val newWine =
                                                    if (config.wineVersion == (glibcWineEntries.firstOrNull() ?: Container.DEFAULT_WINE_VERSION))
                                                        bionicWineEntries.firstOrNull() ?: config.wineVersion
                                                    else config.wineVersion
                                                val newCfg = KeyValueSet(config.graphicsDriverConfig).apply {
                                                    put("version", DefaultVersion.WRAPPER)
                                                    put("syncFrame", "0")
                                                    put("adrenotoolsTurnip", "1")
                                                    put("disablePresentWait", get("disablePresentWait").ifEmpty { "0" })
                                                    if (get("exposedDeviceExtensions").isEmpty()) put("exposedDeviceExtensions", "all")
                                                    if (get("maxDeviceMemory").isEmpty()) put("maxDeviceMemory", "4096")
                                                    if (get("presentMode").isEmpty()) put("presentMode", "mailbox")
                                                    if (get("resourceType").isEmpty()) put("resourceType", "auto")
                                                    if (get("bcnEmulation").isEmpty()) put("bcnEmulation", "auto")
                                                    if (get("bcnEmulationType").isEmpty()) put("bcnEmulationType", "software")
                                                    if (get("bcnEmulationCache").isEmpty()) put("bcnEmulationCache", "0")
                                                }
                                                bionicDriverIndex = 0
                                                wrapperVersionIndex = wrapperOptions.ids
                                                    .indexOfFirst { it.equals(DefaultVersion.WRAPPER, true) }
                                                    .let { if (it >= 0) it else 0 }
                                                syncEveryFrameChecked = false
                                                disablePresentWaitChecked = newCfg.get("disablePresentWait", "0") == "1"
                                                bcnEmulationCacheEnabled = newCfg.get("bcnEmulationCache", "0") == "1"
                                                adrenotoolsTurnipChecked = true
                                                maxDeviceMemoryIndex =
                                                    listOf("0", "512", "1024", "2048", "4096").indexOf("4096").coerceAtLeast(0)

                                                // If transitioning from GLIBC -> BIONIC, set Box64 to default and DXVK to async-1.10.3
                                                val currentConfig = KeyValueSet(config.dxwrapperConfig)
                                                currentConfig.put("version", "async-1.10.3")
                                                currentConfig.put("async", "1")
                                                currentConfig.put("asyncCache", "0")
                                                config = config.copy(dxwrapperConfig = currentConfig.toString())

                                                config = config.copy(
                                                    containerVariant = newVariant,
                                                    wineVersion = newWine,
                                                    graphicsDriver = defaultBionicDriver,
                                                    graphicsDriverVersion = "",
                                                    graphicsDriverConfig = newCfg.toString(),
                                                    box64Version = "0.3.7",
                                                    dxwrapperConfig = currentConfig.toString(),
                                                )
                                            }
                                        },
                                    )
                                    // Wine version only if bionic variant
                                    if (config.containerVariant.equals(Container.BIONIC, ignoreCase = true)) {
                                        val wineIndex = bionicWineOptions.ids.indexOfFirst { it == config.wineVersion }.coerceAtLeast(0)
                                        SettingsListDropdown(
                                            colors = settingsTileColors(),
                                            title = { Text(text = stringResource(R.string.wine_version)) },
                                            value = wineIndex,
                                            items = bionicWineOptions.labels,
                                            itemMuted = bionicWineOptions.muted,
                                            onItemSelected = { idx ->
                                                val selectedId = bionicWineOptions.ids.getOrNull(idx).orEmpty()
                                                val isManifestNotInstalled = bionicWineOptions.muted.getOrNull(idx) == true
                                                val manifestEntry = bionicWineManifestById[selectedId]
                                                if (isManifestNotInstalled && manifestEntry != null) {
                                                    val expectedType = if (selectedId.startsWith("proton", true)) {
                                                        ContentProfile.ContentType.CONTENT_TYPE_PROTON
                                                    } else {
                                                        ContentProfile.ContentType.CONTENT_TYPE_WINE
                                                    }
                                                    launchManifestContentInstall(manifestEntry, expectedType) {
                                                        config = config.copy(wineVersion = selectedId)
                                                    }
                                                    return@SettingsListDropdown
                                                }
                                                config = config.copy(wineVersion = selectedId.ifEmpty { bionicWineOptions.labels[idx] })
                                            },
                                        )
                                    }
                                }
                                // Executable Path dropdown with all EXEs from A: drive
                                ExecutablePathDropdown(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                    value = config.executablePath,
                                    onValueChange = { config = config.copy(executablePath = it) },
                                    containerData = config,
                                )
                                OutlinedTextField(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                    value = config.execArgs,
                                    onValueChange = { config = config.copy(execArgs = it) },
                                    label = { Text(text = stringResource(R.string.exec_arguments)) },
                                    placeholder = { Text(text = stringResource(R.string.exec_arguments_example)) },
                                )
                                val displayNameForLanguage: (String) -> String = { code ->
                                    when (code) {
                                        "schinese" -> "Simplified Chinese"
                                        "tchinese" -> "Traditional Chinese"
                                        "koreana" -> "Korean"
                                        "latam" -> "Spanish (Latin America)"
                                        "brazilian" -> "Portuguese (Brazil)"
                                        else -> code.replaceFirstChar { ch -> ch.titlecase(Locale.getDefault()) }
                                    }
                                }
                                SettingsListDropdown(
                                    enabled = true,
                                    value = languageIndex,
                                    items = languages.map(displayNameForLanguage),
                                    fallbackDisplay = displayNameForLanguage("english"),
                                    onItemSelected = { index ->
                                        languageIndex = index
                                        config = config.copy(language = languages[index])
                                    },
                                    title = { Text(text = stringResource(R.string.language)) },
                                    colors = settingsTileColors(),
                                )
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(text = stringResource(R.string.screen_size)) },
                                    value = screenSizeIndex,
                                    items = screenSizes,
                                    onItemSelected = {
                                        screenSizeIndex = it
                                        if (it == 0) {
                                            showCustomResolutionDialog = true
                                        } else {
                                            applyScreenSizeToConfig()
                                        }
                                    },
                                )
                                // Audio Driver Dropdown
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(text = stringResource(R.string.audio_driver)) },
                                    value = audioDriverIndex,
                                    items = audioDrivers,
                                    onItemSelected = {
                                        audioDriverIndex = it
                                        config = config.copy(audioDriver = StringUtils.parseIdentifier(audioDrivers[it]))
                                    },
                                )
                                SettingsSwitch(
                                    colors = settingsTileColorsAlt(),
                                    title = { Text(text = stringResource(R.string.show_fps)) },
                                    state = config.showFPS,
                                    onCheckedChange = {
                                        config = config.copy(showFPS = it)
                                    },
                                )

                                SettingsSwitch(
                                    colors = settingsTileColorsAlt(),
                                    title = { Text(text = stringResource(R.string.force_dlc)) },
                                    subtitle = { Text(text = stringResource(R.string.force_dlc_description)) },
                                    state = config.forceDlc,
                                    onCheckedChange = {
                                        config = config.copy(forceDlc = it)
                                    },
                                )
                                SettingsSwitch(
                                    colors = settingsTileColorsAlt(),
                                    title = { Text(text = stringResource(R.string.use_legacy_drm)) },
                                    state = config.useLegacyDRM,
                                    onCheckedChange = {
                                        config = config.copy(useLegacyDRM = it)
                                    },
                                )
                                if (!config.useLegacyDRM) {
                                    SettingsSwitch(
                                        colors = settingsTileColorsAlt(),
                                        title = { Text(text = stringResource(R.string.unpack_files)) },
                                        subtitle = { Text(text = stringResource(R.string.unpack_files_description)) },
                                        state = config.unpackFiles,
                                        onCheckedChange = {
                                            config = config.copy(unpackFiles = it)
                                        },
                                    )
                                }
                                SettingsSwitch(
                                    colors = settingsTileColorsAlt(),
                                    title = { Text(text = stringResource(R.string.launch_steam_client_beta)) },
                                    subtitle = { Text(text = stringResource(R.string.launch_steam_client_description)) },
                                    state = config.launchRealSteam,
                                    onCheckedChange = {
                                        config = config.copy(launchRealSteam = it)
                                    },
                                )
                                if (config.launchRealSteam) {
                                    SettingsSwitch(
                                        colors = settingsTileColorsAlt(),
                                        title = { Text(text = stringResource(R.string.allow_steam_updates)) },
                                        subtitle = { Text(text = stringResource(R.string.allow_steam_updates_description)) },
                                        state = config.allowSteamUpdates,
                                        onCheckedChange = {
                                            config = config.copy(allowSteamUpdates = it)
                                        },
                                    )
                                }
                                // Steam Type Dropdown
                                val steamTypeItems = listOf("Normal", "Light", "Ultra Light")
                                val currentSteamTypeIndex = when (config.steamType.lowercase()) {
                                    Container.STEAM_TYPE_LIGHT -> 1
                                    Container.STEAM_TYPE_ULTRALIGHT -> 2
                                    else -> 0
                                }
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(text = stringResource(R.string.steam_type)) },
                                    value = currentSteamTypeIndex,
                                    items = steamTypeItems,
                                    onItemSelected = {
                                        val type = when (it) {
                                            1 -> Container.STEAM_TYPE_LIGHT
                                            2 -> Container.STEAM_TYPE_ULTRALIGHT
                                            else -> Container.STEAM_TYPE_NORMAL
                                        }
                                        config = config.copy(steamType = type)
                                    },
                                )
                            }
                            if (selectedTab == 1) SettingsGroup() {
                                if (config.containerVariant.equals(Container.BIONIC, ignoreCase = true)) {
                                    // Bionic: Graphics Driver (Wrapper/Wrapper-v2)
                                    SettingsListDropdown(
                                        colors = settingsTileColors(),
                                        title = { Text(text = stringResource(R.string.graphics_driver)) },
                                        value = bionicDriverIndex,
                                        items = bionicGraphicsDrivers,
                                        onItemSelected = { idx ->
                                            bionicDriverIndex = idx
                                            config = config.copy(graphicsDriver = StringUtils.parseIdentifier(bionicGraphicsDrivers[idx]))
                                        },
                                    )
                                    // Bionic: Graphics Driver Version (stored in graphicsDriverConfig.version)
                                    SettingsListDropdown(
                                        colors = settingsTileColors(),
                                        title = { Text(text = stringResource(R.string.graphics_driver_version)) },
                                        value = wrapperVersionIndex,
                                        items = wrapperOptions.labels,
                                        itemMuted = wrapperOptions.muted,
                                        onItemSelected = { idx ->
                                            wrapperVersionIndex = idx
                                            val selectedId = wrapperOptions.ids.getOrNull(idx).orEmpty()
                                            val isManifestNotInstalled = wrapperOptions.muted.getOrNull(idx) == true
                                            val manifestEntry = wrapperManifestById[selectedId]
                                            if (isManifestNotInstalled && manifestEntry != null) {
                                                launchManifestDriverInstall(manifestEntry) {
                                                    val cfg = KeyValueSet(config.graphicsDriverConfig)
                                                    cfg.put("version", selectedId)
                                                    config = config.copy(graphicsDriverConfig = cfg.toString())
                                                }
                                                return@SettingsListDropdown
                                            }
                                            val cfg = KeyValueSet(config.graphicsDriverConfig)
                                            cfg.put("version", selectedId)
                                            config = config.copy(graphicsDriverConfig = cfg.toString())
                                        },
                                    )
                                    DxWrapperSection()
                                    // Bionic: Exposed Vulkan Extensions (same UI as Vortek)
                                    SettingsMultiListDropdown(
                                        colors = settingsTileColors(),
                                        title = { Text(text = stringResource(R.string.exposed_vulkan_extensions)) },
                                        values = exposedExtIndices,
                                        items = gpuExtensions,
                                        fallbackDisplay = "all",
                                        onItemSelected = { idx ->
                                            exposedExtIndices =
                                                if (exposedExtIndices.contains(idx)) exposedExtIndices.filter { it != idx } else exposedExtIndices + idx
                                            val cfg = KeyValueSet(config.graphicsDriverConfig)
                                            val allSelected = exposedExtIndices.size == gpuExtensions.size
                                            if (allSelected) cfg.put("exposedDeviceExtensions", "all") else cfg.put(
                                                "exposedDeviceExtensions",
                                                exposedExtIndices.sorted().joinToString("|") { gpuExtensions[it] },
                                            )
                                            // Maintain blacklist as the complement of exposed selections
                                            val blacklisted = if (allSelected) "" else
                                                gpuExtensions.indices
                                                    .filter { it !in exposedExtIndices }
                                                    .sorted()
                                                    .joinToString(",") { gpuExtensions[it] }
                                            cfg.put("blacklistedExtensions", blacklisted)
                                            config = config.copy(graphicsDriverConfig = cfg.toString())
                                        },
                                    )
                                    // Bionic: Max Device Memory (same as Vortek)
                                    run {
                                        val memValues = listOf("0", "512", "1024", "2048", "4096")
                                        val memLabels = listOf("0 MB", "512 MB", "1024 MB", "2048 MB", "4096 MB")
                                        SettingsListDropdown(
                                            colors = settingsTileColors(),
                                            title = { Text(text = stringResource(R.string.max_device_memory)) },
                                            value = maxDeviceMemoryIndex.coerceIn(0, memValues.lastIndex),
                                            items = memLabels,
                                            onItemSelected = { idx ->
                                                maxDeviceMemoryIndex = idx
                                                val cfg = KeyValueSet(config.graphicsDriverConfig)
                                                cfg.put("maxDeviceMemory", memValues[idx])
                                                config = config.copy(graphicsDriverConfig = cfg.toString())
                                            },
                                        )
                                    }
                                    // Bionic: Use Adrenotools Turnip
                                    SettingsSwitch(
                                        colors = settingsTileColorsAlt(),
                                        title = { Text(text = stringResource(R.string.use_adrenotools_turnip)) },
                                        state = adrenotoolsTurnipChecked,
                                        onCheckedChange = { checked ->
                                            adrenotoolsTurnipChecked = checked
                                            val cfg = KeyValueSet(config.graphicsDriverConfig)
                                            cfg.put("adrenotoolsTurnip", if (checked) "1" else "0")
                                            config = config.copy(graphicsDriverConfig = cfg.toString())
                                        },
                                    )
                                    SettingsListDropdown(
                                        colors = settingsTileColors(),
                                        title = { Text(text = stringResource(R.string.present_modes)) },
                                        value = presentModeIndex.coerceIn(0, presentModes.lastIndex.coerceAtLeast(0)),
                                        items = presentModes,
                                        onItemSelected = { idx ->
                                            presentModeIndex = idx
                                            val cfg = KeyValueSet(config.graphicsDriverConfig)
                                            cfg.put("presentMode", presentModes[idx])
                                            config = config.copy(graphicsDriverConfig = cfg.toString())
                                        },
                                    )
                                    SettingsListDropdown(
                                        colors = settingsTileColors(),
                                        title = { Text(text = stringResource(R.string.resource_type)) },
                                        value = resourceTypeIndex.coerceIn(0, resourceTypes.lastIndex.coerceAtLeast(0)),
                                        items = resourceTypes,
                                        onItemSelected = { idx ->
                                            resourceTypeIndex = idx
                                            val cfg = KeyValueSet(config.graphicsDriverConfig)
                                            cfg.put("resourceType", resourceTypes[idx])
                                            config = config.copy(graphicsDriverConfig = cfg.toString())
                                        },
                                    )
                                    SettingsListDropdown(
                                        colors = settingsTileColors(),
                                        title = { Text(text = stringResource(R.string.bcn_emulation)) },
                                        value = bcnEmulationIndex.coerceIn(0, bcnEmulationEntries.lastIndex.coerceAtLeast(0)),
                                        items = bcnEmulationEntries,
                                        onItemSelected = { idx ->
                                            bcnEmulationIndex = idx
                                            val cfg = KeyValueSet(config.graphicsDriverConfig)
                                            cfg.put("bcnEmulation", bcnEmulationEntries[idx])
                                            config = config.copy(graphicsDriverConfig = cfg.toString())
                                        },
                                    )
                                    SettingsListDropdown(
                                        colors = settingsTileColors(),
                                        title = { Text(text = stringResource(R.string.bcn_emulation_type)) },
                                        value = bcnEmulationTypeIndex.coerceIn(0, bcnEmulationTypeEntries.lastIndex.coerceAtLeast(0)),
                                        items = bcnEmulationTypeEntries,
                                        onItemSelected = { idx ->
                                            bcnEmulationTypeIndex = idx
                                            val cfg = KeyValueSet(config.graphicsDriverConfig)
                                            cfg.put("bcnEmulationType", bcnEmulationTypeEntries[idx])
                                            config = config.copy(graphicsDriverConfig = cfg.toString())
                                        },
                                    )
                                    SettingsSwitch(
                                        colors = settingsTileColorsAlt(),
                                        title = { Text(text = stringResource(R.string.bcn_emulation_cache)) },
                                        state = bcnEmulationCacheEnabled,
                                        onCheckedChange = { checked ->
                                            bcnEmulationCacheEnabled = checked
                                            val cfg = KeyValueSet(config.graphicsDriverConfig)
                                            cfg.put("bcnEmulationCache", if (checked) "1" else "0")
                                            config = config.copy(graphicsDriverConfig = cfg.toString())
                                        },
                                    )
                                    SettingsSwitch(
                                        colors = settingsTileColorsAlt(),
                                        title = { Text(text = stringResource(R.string.disable_present_wait)) },
                                        state = disablePresentWaitChecked,
                                        onCheckedChange = { checked ->
                                            disablePresentWaitChecked = checked
                                            val cfg = KeyValueSet(config.graphicsDriverConfig)
                                            cfg.put("disablePresentWait", if (checked) "1" else "0")
                                            config = config.copy(graphicsDriverConfig = cfg.toString())
                                        },
                                    )
                                    SettingsSwitch(
                                        colors = settingsTileColorsAlt(),
                                        title = { Text(text = stringResource(R.string.sync_frame)) },
                                        state = syncEveryFrameChecked,
                                        onCheckedChange = { checked ->
                                            syncEveryFrameChecked = checked
                                            val cfg = KeyValueSet(config.graphicsDriverConfig)
                                            cfg.put("syncFrame", if (checked) "1" else "0")
                                            config = config.copy(graphicsDriverConfig = cfg.toString())
                                        },
                                    )
                                    SettingsListDropdown(
                                        colors = settingsTileColors(),
                                        title = { Text(text = stringResource(R.string.sharpness_effect)) },
                                        value = sharpnessEffectIndex.coerceIn(0, sharpnessEffects.lastIndex.coerceAtLeast(0)),
                                        items = sharpnessDisplayItems,
                                        onItemSelected = { idx ->
                                            sharpnessEffectIndex = idx
                                            config = config.copy(sharpnessEffect = sharpnessEffects[idx])
                                        },
                                    )
                                    val selectedBoost = sharpnessEffects
                                        .getOrNull(sharpnessEffectIndex)
                                        ?.equals("None", ignoreCase = true)
                                        ?.not() ?: false
                                    if (selectedBoost) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        ) {
                                            Text(text = stringResource(R.string.sharpness_level))
                                            Slider(
                                                value = sharpnessLevel.toFloat(),
                                                onValueChange = { newValue ->
                                                    val clamped = newValue.roundToInt().coerceIn(0, 100)
                                                    sharpnessLevel = clamped
                                                    config = config.copy(sharpnessLevel = clamped)
                                                },
                                                valueRange = 0f..100f,
                                            )
                                            Text(text = "${sharpnessLevel}%")
                                        }
                                        Column(
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        ) {
                                            Text(text = stringResource(R.string.sharpness_denoise))
                                            Slider(
                                                value = sharpnessDenoise.toFloat(),
                                                onValueChange = { newValue ->
                                                    val clamped = newValue.roundToInt().coerceIn(0, 100)
                                                    sharpnessDenoise = clamped
                                                    config = config.copy(sharpnessDenoise = clamped)
                                                },
                                                valueRange = 0f..100f,
                                            )
                                            Text(text = "${sharpnessDenoise}%")
                                        }
                                    }
                                } else {
                                    // Non-bionic: existing driver/version UI and Vortek-specific options
                                    SettingsListDropdown(
                                        colors = settingsTileColors(),
                                        title = { Text(text = stringResource(R.string.graphics_driver)) },
                                        value = graphicsDriverIndex,
                                        items = graphicsDrivers,
                                        onItemSelected = {
                                            graphicsDriverIndex = it
                                            config = config.copy(graphicsDriver = StringUtils.parseIdentifier(graphicsDrivers[it]))
                                            // Reset version index when driver changes
                                            graphicsDriverVersionIndex = 0
                                            config = config.copy(graphicsDriverVersion = "")
                                        },
                                    )
                                    SettingsListDropdown(
                                        colors = settingsTileColors(),
                                        title = { Text(text = stringResource(R.string.graphics_driver_version)) },
                                        value = graphicsDriverVersionIndex,
                                        items = getVersionsForDriver(),
                                        onItemSelected = {
                                            graphicsDriverVersionIndex = it
                                            val selectedVersion = if (it == 0) "" else getVersionsForDriver()[it]
                                            config = config.copy(graphicsDriverVersion = selectedVersion)
                                        },
                                    )
                                    DxWrapperSection()
                                    // Vortek/Adreno specific settings
                                    run {
                                        val driverType = StringUtils.parseIdentifier(graphicsDrivers[graphicsDriverIndex])
                                        val isVortekLike = config.containerVariant.equals(Container.GLIBC) && driverType == "vortek" || driverType == "adreno" || driverType == "sd-8-elite"
                                        if (isVortekLike) {
                                            // Vulkan Max Version
                                            val vkVersions = listOf("1.0", "1.1", "1.2", "1.3")
                                            SettingsListDropdown(
                                                colors = settingsTileColors(),
                                                title = { Text(text = stringResource(R.string.vulkan_version)) },
                                                value = vkMaxVersionIndex.coerceIn(0, 3),
                                                items = vkVersions,
                                                onItemSelected = { idx ->
                                                    vkMaxVersionIndex = idx
                                                    val cfg = KeyValueSet(config.graphicsDriverConfig)
                                                    cfg.put("vkMaxVersion", vkVersions[idx])
                                                    config = config.copy(graphicsDriverConfig = cfg.toString())
                                                },
                                            )
                                            // Exposed Extensions (multi-select)
                                            SettingsMultiListDropdown(
                                                colors = settingsTileColors(),
                                                title = { Text(text = stringResource(R.string.exposed_vulkan_extensions)) },
                                                values = exposedExtIndices,
                                                items = gpuExtensions,
                                                fallbackDisplay = "all",
                                                onItemSelected = { idx ->
                                                    exposedExtIndices =
                                                        if (exposedExtIndices.contains(idx)) exposedExtIndices.filter { it != idx } else exposedExtIndices + idx
                                                    val cfg = KeyValueSet(config.graphicsDriverConfig)
                                                    val allSelected = exposedExtIndices.size == gpuExtensions.size
                                                    if (allSelected) cfg.put("exposedDeviceExtensions", "all") else cfg.put(
                                                        "exposedDeviceExtensions",
                                                        exposedExtIndices.sorted().joinToString("|") { gpuExtensions[it] },
                                                    )
                                                    // Maintain blacklist as the complement of exposed selections
                                                    val blacklisted = if (allSelected) "" else
                                                        gpuExtensions.indices
                                                            .filter { it !in exposedExtIndices }
                                                            .sorted()
                                                            .joinToString(",") { gpuExtensions[it] }
                                                    cfg.put("blacklistedExtensions", blacklisted)
                                                    config = config.copy(graphicsDriverConfig = cfg.toString())
                                                },
                                            )
                                            // Image Cache Size
                                            val imageSizes = listOf("64", "128", "256", "512", "1024")
                                            val imageLabels = listOf("64", "128", "256", "512", "1024").map { "$it MB" }
                                            SettingsListDropdown(
                                                colors = settingsTileColors(),
                                                title = { Text(text = stringResource(R.string.image_cache_size)) },
                                                value = imageCacheIndex.coerceIn(0, imageSizes.lastIndex),
                                                items = imageLabels,
                                                onItemSelected = { idx ->
                                                    imageCacheIndex = idx
                                                    val cfg = KeyValueSet(config.graphicsDriverConfig)
                                                    cfg.put("imageCacheSize", imageSizes[idx])
                                                    config = config.copy(graphicsDriverConfig = cfg.toString())
                                                },
                                            )
                                            // Max Device Memory
                                            val memValues = listOf("0", "512", "1024", "2048", "4096")
                                            val memLabels = listOf("0 MB", "512 MB", "1024 MB", "2048 MB", "4096 MB")
                                            SettingsListDropdown(
                                                colors = settingsTileColors(),
                                                title = { Text(text = stringResource(R.string.max_device_memory)) },
                                                value = maxDeviceMemoryIndex.coerceIn(0, memValues.lastIndex),
                                                items = memLabels,
                                                onItemSelected = { idx ->
                                                    maxDeviceMemoryIndex = idx
                                                    val cfg = KeyValueSet(config.graphicsDriverConfig)
                                                    cfg.put("maxDeviceMemory", memValues[idx])
                                                    config = config.copy(graphicsDriverConfig = cfg.toString())
                                                },
                                            )
                                        }
                                    }
                                }
                                SettingsSwitch(
                                    colors = settingsTileColorsAlt(),
                                    title = { Text(text = stringResource(R.string.use_dri3)) },
                                    subtitle = { Text(text = stringResource(R.string.use_dri3_description)) },
                                    state = config.useDRI3,
                                    onCheckedChange = {
                                        config = config.copy(useDRI3 = it)
                                    }
                                )
                            }
                            if (selectedTab == 2) SettingsGroup() {
                                if (config.containerVariant.equals(Container.BIONIC, ignoreCase = true)) {
                                    // Bionic: Emulators
                                    val wineIsX8664 = config.wineVersion.contains("x86_64", true)
                                    val wineIsArm64Ec = config.wineVersion.contains("arm64ec", true)

                                    // FEXCore Settings (only when Bionic + Wine arm64ec) placed under Box64 settings
                                    run {
                                        if (wineIsArm64Ec) {
                                            SettingsGroup() {
                                                val fexcoreIndex = fexcoreOptions.ids.indexOfFirst { it == config.fexcoreVersion }.coerceAtLeast(0)
                                                SettingsListDropdown(
                                                    colors = settingsTileColors(),
                                                    title = { Text(text = stringResource(R.string.fexcore_version)) },
                                                    value = fexcoreIndex,
                                                    items = fexcoreOptions.labels,
                                                    itemMuted = fexcoreOptions.muted,
                                                    onItemSelected = { idx ->
                                                        val selectedId = fexcoreOptions.ids.getOrNull(idx).orEmpty()
                                                        val isManifestNotInstalled = fexcoreOptions.muted.getOrNull(idx) == true
                                                        val manifestEntry = fexcoreManifestById[selectedId]
                                                        if (isManifestNotInstalled && manifestEntry != null) {
                                                            launchManifestContentInstall(
                                                                manifestEntry,
                                                                ContentProfile.ContentType.CONTENT_TYPE_FEXCORE,
                                                            ) {
                                                                config = config.copy(fexcoreVersion = selectedId)
                                                            }
                                                            return@SettingsListDropdown
                                                        }
                                                        config = config.copy(fexcoreVersion = selectedId.ifEmpty { fexcoreOptions.labels[idx] })
                                                    },
                                                )
                                            }
                                        }
                                    }

                                    // 64-bit Emulator (locked based on wine arch)
                                    SettingsListDropdown(
                                        colors = settingsTileColors(),
                                        title = { Text(text = stringResource(R.string.emulator_64bit)) },
                                        value = emulator64Index,
                                        items = emulatorEntries,
                                        enabled = false, // Always non-editable per requirements
                                        onItemSelected = { /* locked */ },
                                    )
                                    // Ensure correct locked value displayed
                                    LaunchedEffect(wineIsX8664, wineIsArm64Ec) {
                                        emulator64Index = if (wineIsX8664) 1 else 0
                                    }

                                    // 32-bit Emulator
                                    SettingsListDropdown(
                                        colors = settingsTileColors(),
                                        title = { Text(text = stringResource(R.string.emulator_32bit)) },
                                        value = emulator32Index,
                                        items = emulatorEntries,
                                        enabled = when {
                                            wineIsX8664 -> false // locked to Box64
                                            wineIsArm64Ec -> true // editable between FEXCore and Box64
                                            else -> true
                                        },
                                        onItemSelected = { idx ->
                                            emulator32Index = idx
                                            // Persist to config.emulator
                                            config = config.copy(emulator = emulatorEntries[idx])
                                        },
                                    )
                                    // Enforce locking defaults when variant/wine changes
                                    LaunchedEffect(wineIsX8664) {
                                        if (wineIsX8664) {
                                            emulator32Index = 1
                                            if (config.emulator != emulatorEntries[1]) {
                                                config = config.copy(emulator = emulatorEntries[1])
                                            }
                                        }
                                    }
                                    LaunchedEffect(wineIsArm64Ec) {
                                        if (wineIsArm64Ec) {
                                            if (emulator32Index !in 0..1) emulator32Index = 0
                                            if (config.emulator.isEmpty()) {
                                                config = config.copy(emulator = emulatorEntries[0])
                                            }
                                        }
                                    }
                                }
                                val box64OptionList = getVersionsForBox64()
                                val box64Index = box64OptionList.ids.indexOfFirst { it == config.box64Version }.coerceAtLeast(0)
                                val box64ManifestMap = if (config.wineVersion.contains("arm64ec", true)) {
                                    wowBox64ManifestById
                                } else {
                                    box64ManifestById
                                }
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(text = stringResource(R.string.box64_version)) },
                                    value = box64Index,
                                    items = box64OptionList.labels,
                                    itemMuted = box64OptionList.muted,
                                    onItemSelected = { idx ->
                                        val selectedId = box64OptionList.ids.getOrNull(idx).orEmpty()
                                        val isManifestNotInstalled = box64OptionList.muted.getOrNull(idx) == true
                                        val manifestEntry = box64ManifestMap[selectedId.lowercase(Locale.ENGLISH)]
                                        if (isManifestNotInstalled && manifestEntry != null) {
                                            val expectedType = if (config.wineVersion.contains("arm64ec", true)) {
                                                ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64
                                            } else {
                                                ContentProfile.ContentType.CONTENT_TYPE_BOX64
                                            }
                                            launchManifestContentInstall(manifestEntry, expectedType) {
                                                config = config.copy(box64Version = selectedId)
                                            }
                                            return@SettingsListDropdown
                                        }
                                        config = config.copy(box64Version = selectedId.ifEmpty { StringUtils.parseIdentifier(box64OptionList.labels[idx]) })
                                    },
                                )
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(text = stringResource(R.string.box64_preset)) },
                                    value = box64Presets.indexOfFirst { it.id == config.box64Preset },
                                    items = box64Presets.map { it.name },
                                    onItemSelected = {
                                        config = config.copy(
                                            box64Preset = box64Presets[it].id,
                                        )
                                    },
                                )
                                // FEXCore Preset (only when Bionic + Wine arm64ec)
                                if (config.containerVariant.equals(Container.BIONIC, ignoreCase = true)
                                    && config.wineVersion.contains("arm64ec", ignoreCase = true)) {
                                    SettingsListDropdown(
                                        colors = settingsTileColors(),
                                        title = { Text(text = stringResource(R.string.fexcore_preset)) },
                                        value = fexcorePresets.indexOfFirst { it.id == config.fexcorePreset }.coerceAtLeast(0),
                                        items = fexcorePresets.map { it.name },
                                        onItemSelected = {
                                            config = config.copy(
                                                fexcorePreset = fexcorePresets[it].id,
                                            )
                                        },
                                    )
                                }
                            }
                            if (selectedTab == 3) SettingsGroup() {
                                if (!default) {
                                    SettingsSwitch(
                                        colors = settingsTileColorsAlt(),
                                        title = { Text(text = stringResource(R.string.use_sdl_api)) },
                                        state = config.sdlControllerAPI,
                                        onCheckedChange = {
                                            config = config.copy(sdlControllerAPI = it)
                                        },
                                    )
                                }
                                SettingsSwitch(
                                    colors = settingsTileColorsAlt(),
                                    title = { Text(text = stringResource(R.string.use_steam_input)) },
                                    state = config.useSteamInput,
                                    onCheckedChange = {
                                        config = config.copy(useSteamInput = it)
                                    },
                                )
                                // Enable XInput API
                                SettingsSwitch(
                                    colors = settingsTileColorsAlt(),
                                    title = { Text(text = stringResource(R.string.enable_xinput_api)) },
                                    state = config.enableXInput,
                                    onCheckedChange = {
                                        config = config.copy(enableXInput = it)
                                    }
                                )
                                // Enable DirectInput API
                                SettingsSwitch(
                                    colors = settingsTileColorsAlt(),
                                    title = { Text(text = stringResource(R.string.enable_directinput_api)) },
                                    state = config.enableDInput,
                                    onCheckedChange = {
                                        config = config.copy(enableDInput = it)
                                    }
                                )
                                // DirectInput Mapper Type
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(text = stringResource(R.string.directinput_mapper_type)) },
                                    value = if (config.dinputMapperType == 1.toByte()) 0 else 1,
                                    items = listOf("Standard", "XInput Mapper"),
                                    onItemSelected = { index ->
                                        config = config.copy(dinputMapperType = if (index == 0) 1 else 2)
                                    }
                                )
                                // Disable external mouse input
                                SettingsSwitch(
                                    colors = settingsTileColorsAlt(),
                                    title = { Text(text = stringResource(R.string.disable_mouse_input)) },
                                    state = config.disableMouseInput,
                                    onCheckedChange = { config = config.copy(disableMouseInput = it) }
                                )

                                // Touchscreen mode
                                SettingsSwitch(
                                    colors = settingsTileColorsAlt(),
                                    title = { Text(text = stringResource(R.string.touchscreen_mode)) },
                                    subtitle = { Text(text = stringResource(R.string.touchscreen_mode_description)) },
                                    state = config.touchscreenMode,
                                    onCheckedChange = { config = config.copy(touchscreenMode = it) }
                                )
                                // External display handling
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(text = stringResource(R.string.external_display_input)) },
                                    subtitle = { Text(text = stringResource(R.string.external_display_input_subtitle)) },
                                    value = externalDisplayModeIndex,
                                    items = externalDisplayModes,
                                    onItemSelected = { index ->
                                        externalDisplayModeIndex = index
                                        config = config.copy(
                                            externalDisplayMode = when (index) {
                                                1 -> Container.EXTERNAL_DISPLAY_MODE_TOUCHPAD
                                                2 -> Container.EXTERNAL_DISPLAY_MODE_KEYBOARD
                                                3 -> Container.EXTERNAL_DISPLAY_MODE_HYBRID
                                                else -> Container.EXTERNAL_DISPLAY_MODE_OFF
                                            },
                                        )
                                    },
                                )
                                SettingsSwitch(
                                    colors = settingsTileColorsAlt(),
                                    title = { Text(text = stringResource(R.string.external_display_swap)) },
                                    subtitle = { Text(text = stringResource(R.string.external_display_swap_subtitle)) },
                                    state = config.externalDisplaySwap,
                                    onCheckedChange = { config = config.copy(externalDisplaySwap = it) }
                                )
                            }
                            if (selectedTab == 4) SettingsGroup() {
                                // TODO: add desktop settings
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(text = stringResource(R.string.renderer)) },
                                    value = gpuNameIndex,
                                    items = gpuCards.values.map { it.name },
                                    onItemSelected = {
                                        gpuNameIndex = it
                                        config = config.copy(videoPciDeviceID = gpuCards.values.toList()[it].deviceId)
                                    },
                                )
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(text = stringResource(R.string.gpu_name)) },
                                    value = gpuNameIndex,
                                    items = gpuCards.values.map { it.name },
                                    onItemSelected = {
                                        gpuNameIndex = it
                                        config = config.copy(videoPciDeviceID = gpuCards.values.toList()[it].deviceId)
                                    },
                                )
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(text = stringResource(R.string.offscreen_rendering_mode)) },
                                    value = renderingModeIndex,
                                    items = renderingModes,
                                    onItemSelected = {
                                        renderingModeIndex = it
                                        config = config.copy(offScreenRenderingMode = renderingModes[it].lowercase())
                                    },
                                )
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(text = stringResource(R.string.video_memory_size)) },
                                    value = videoMemIndex,
                                    items = videoMemSizes,
                                    onItemSelected = {
                                        videoMemIndex = it
                                        config = config.copy(videoMemorySize = StringUtils.parseNumber(videoMemSizes[it]))
                                    },
                                )
                                SettingsSwitch(
                                    colors = settingsTileColorsAlt(),
                                    title = { Text(text = stringResource(R.string.enable_csmt)) },
                                    state = config.csmt,
                                    onCheckedChange = {
                                        config = config.copy(csmt = it)
                                    },
                                )
                                SettingsSwitch(
                                    colors = settingsTileColorsAlt(),
                                    title = { Text(text = stringResource(R.string.enable_strict_shader_math)) },
                                    state = config.strictShaderMath,
                                    onCheckedChange = {
                                        config = config.copy(strictShaderMath = it)
                                    },
                                )
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(text = stringResource(R.string.mouse_warp_override)) },
                                    value = mouseWarpIndex,
                                    items = mouseWarps,
                                    onItemSelected = {
                                        mouseWarpIndex = it
                                        config = config.copy(mouseWarpOverride = mouseWarps[it].lowercase())
                                    },
                                )
                            }
                            if (selectedTab == 5) SettingsGroup() {
                                for (wincomponent in KeyValueSet(config.wincomponents)) {
                                    val compId = wincomponent[0]
                                    val compNameRes = winComponentsItemTitleRes(compId)
                                    val compValue = wincomponent[1].toInt()
                                    SettingsListDropdown(
                                        colors = settingsTileColors(),
                                        title = { Text(stringResource(id = compNameRes)) },
                                        subtitle = { Text(if (compId.startsWith("direct")) "DirectX" else "General") },
                                        value = compValue,
                                        items = winCompOpts,
                                        onItemSelected = {
                                            config = config.copy(
                                                wincomponents = config.wincomponents.replace("$compId=$compValue", "$compId=$it"),
                                            )
                                        },
                                    )
                                }
                            }
                            if (selectedTab == 6) SettingsGroup() {
                                val envVars = EnvVars(config.envVars)
                                if (config.envVars.isNotEmpty()) {
                                    SettingsEnvVars(
                                        colors = settingsTileColors(),
                                        envVars = envVars,
                                        onEnvVarsChange = {
                                            config = config.copy(envVars = it.toString())
                                        },
                                        knownEnvVars = EnvVarInfo.KNOWN_ENV_VARS,
                                        envVarAction = {
                                            IconButton(
                                                onClick = {
                                                    envVars.remove(it)
                                                    config = config.copy(
                                                        envVars = envVars.toString(),
                                                    )
                                                },
                                                content = {
                                                    Icon(Icons.Filled.Delete, contentDescription = "Delete variable")
                                                },
                                            )
                                        },
                                    )
                                } else {
                                    SettingsCenteredLabel(
                                        colors = settingsTileColors(),
                                        title = { Text(text = stringResource(R.string.no_environment_variables)) },
                                    )
                                }
                                SettingsMenuLink(
                                    title = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.Center,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.AddCircleOutline,
                                                contentDescription = "Add environment variable",
                                            )
                                        }
                                    },
                                    onClick = {
                                        showEnvVarCreateDialog = true
                                    },
                                )
                            }
                            if (selectedTab == 7) SettingsGroup() {
                                // TODO: make the game drive un-deletable
                                // val directoryLauncher = rememberLauncherForActivityResult(
                                //     ActivityResultContracts.OpenDocumentTree()
                                // ) { uri ->
                                //     uri?.let {
                                //         // Handle the selected directory URI
                                //         val driveLetter = Container.getNextAvailableDriveLetter(config.drives)
                                //         config = config.copy(drives = "${config.drives}$driveLetter:${uri.path}")
                                //     }
                                // }

                                if (config.drives.isNotEmpty()) {
                                    for (drive in Container.drivesIterator(config.drives)) {
                                        val driveLetter = drive[0]
                                        val drivePath = drive[1]
                                        SettingsMenuLink(
                                            colors = settingsTileColors(),
                                            title = { Text(driveLetter) },
                                            subtitle = { Text(drivePath) },
                                            onClick = {},
                                            action = if (driveLetter !in nonDeletableDriveLetters) {
                                                {
                                                    IconButton(
                                                        onClick = {
                                                            // Rebuild drives string excluding the drive to delete
                                                            val drivesBuilder = StringBuilder()
                                                            for (existingDrive in Container.drivesIterator(config.drives)) {
                                                                if (existingDrive[0] != driveLetter) {
                                                                    drivesBuilder.append("${existingDrive[0]}:${existingDrive[1]}")
                                                                }
                                                            }
                                                            config = config.copy(
                                                                drives = drivesBuilder.toString(),
                                                            )
                                                        },
                                                        content = {
                                                            Icon(
                                                                Icons.Filled.Delete,
                                                                contentDescription = "Delete drive",
                                                                tint = MaterialTheme.colorScheme.error,
                                                            )
                                                        },
                                                    )
                                                }
                                            } else {
                                                null
                                            },
                                        )
                                    }
                                } else {
                                    SettingsCenteredLabel(
                                        colors = settingsTileColors(),
                                        title = { Text(text = stringResource(R.string.no_drives)) },
                                    )
                                }

                                SettingsMenuLink(
                                    title = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.Center,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.AddCircleOutline,
                                                contentDescription = "Add environment variable",
                                            )
                                        }
                                    },
                                    onClick = {
                                        if (availableDriveLetters.isEmpty()) {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.no_available_drive_letters),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                            return@SettingsMenuLink
                                        }
                                        selectedDriveLetter = availableDriveLetters.first()
                                        driveLetterMenuExpanded = false
                                        showAddDriveDialog = true
                                    },
                                )
                            }
                            if (selectedTab == 8) SettingsGroup() {
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(text = stringResource(R.string.startup_selection)) },
                                    value = config.startupSelection.toInt().takeIf { it in getStartupSelectionOptions().indices } ?: 1,
                                    items = getStartupSelectionOptions(),
                                    onItemSelected = {
                                        config = config.copy(
                                            startupSelection = it.toByte(),
                                        )
                                    },
                                )
                                SettingsCPUList(
                                    colors = settingsTileColors(),
                                    title = { Text(text = stringResource(R.string.processor_affinity)) },
                                    value = config.cpuList,
                                    onValueChange = {
                                        config = config.copy(
                                            cpuList = it,
                                        )
                                    },
                                )
                                SettingsCPUList(
                                    colors = settingsTileColors(),
                                    title = { Text(text = stringResource(R.string.processor_affinity_32bit)) },
                                    value = config.cpuListWoW64,
                                    onValueChange = { config = config.copy(cpuListWoW64 = it) },
                                )
                            }
                        }
                    }
                }
            }
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_ContainerConfigDialog() {
    PluviaTheme {
        val previewConfig = ContainerData(
            name = "Preview Container",
            screenSize = "854x480",
            envVars = "ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact",
            graphicsDriver = "vortek",
            graphicsDriverVersion = "",
            graphicsDriverConfig = "",
            dxwrapper = "dxvk",
            dxwrapperConfig = "",
            audioDriver = "alsa",
            wincomponents = "direct3d=1,directsound=1,directmusic=0,directshow=0,directplay=0,vcrun2010=1,wmdecoder=1,opengl=0",
            drives = "",
            execArgs = "",
            executablePath = "",
            installPath = "",
            showFPS = false,
            launchRealSteam = false,
            allowSteamUpdates = false,
            steamType = "normal",
            cpuList = "0,1,2,3",
            cpuListWoW64 = "0,1,2,3",
            wow64Mode = true,
            startupSelection = 1,
            box86Version = com.winlator.core.DefaultVersion.BOX86,
            box64Version = com.winlator.core.DefaultVersion.BOX64,
            box86Preset = com.winlator.box86_64.Box86_64Preset.COMPATIBILITY,
            box64Preset = com.winlator.box86_64.Box86_64Preset.COMPATIBILITY,
            desktopTheme = com.winlator.core.WineThemeManager.DEFAULT_DESKTOP_THEME,
            containerVariant = "glibc",
            wineVersion = com.winlator.core.WineInfo.MAIN_WINE_VERSION.identifier(),
            emulator = "FEXCore",
            fexcoreVersion = com.winlator.core.DefaultVersion.FEXCORE,
            fexcoreTSOMode = "Fast",
            fexcoreX87Mode = "Fast",
            fexcoreMultiBlock = "Disabled",
            language = "english",
        )
        ContainerConfigDialog(
            visible = true,
            default = false,
            title = stringResource(R.string.container_config_title),
            initialConfig = previewConfig,
            onDismissRequest = {},
            onSave = {},
        )
    }
}

/**
 * Editable dropdown for selecting executable paths from the container's A: drive
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExecutablePathDropdown(
    modifier: Modifier = Modifier,
    value: String,
    onValueChange: (String) -> Unit,
    containerData: ContainerData,
) {
    var expanded by remember { mutableStateOf(false) }
    var executables by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val context = LocalContext.current

    // Load executables from A: drive when component is first created
    LaunchedEffect(containerData.drives) {
        isLoading = true
        executables = withContext(Dispatchers.IO) {
            ContainerUtils.scanExecutablesInADrive(containerData.drives)
        }
        isLoading = false
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = true,
            label = { Text(stringResource(R.string.container_config_executable_path)) },
            placeholder = { Text(stringResource(R.string.container_config_executable_path_placeholder)) },
            trailingIcon = {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            singleLine = true
        )

        if (!isLoading && executables.isNotEmpty()) {
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                executables.forEach { executable ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = executable.substringAfterLast('\\'),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (executable.contains('\\')) {
                                    Text(
                                        text = executable.substringBeforeLast('\\'),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        onClick = {
                            onValueChange(executable)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
