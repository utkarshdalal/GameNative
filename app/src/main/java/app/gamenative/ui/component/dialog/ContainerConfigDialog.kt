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
import app.gamenative.ui.component.settings.SettingsListDropdown
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
import com.winlator.core.envvars.EnvVars
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
internal fun winComponentsItemTitleRes(string: String): Int {
    return when (string) {
        "direct3d" -> R.string.direct3d
        "directsound" -> R.string.directsound
        "directmusic" -> R.string.directmusic
        "directplay" -> R.string.directplay
        "directshow" -> R.string.directshow
        "directx" -> R.string.directx
        "vcrun2010" -> R.string.vcrun2010
        "wmdecoder" -> R.string.wmdecoder
        "opengl" -> R.string.opengl
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

        val configState = rememberSaveable(stateSaver = ContainerData.Saver) {
            mutableStateOf(initialConfig)
        }
        var config by configState

        val screenSizes = stringArrayResource(R.array.screen_size_entries).toList()
        val baseGraphicsDrivers = stringArrayResource(R.array.graphics_driver_entries).toList()
        val graphicsDriversRef = remember { mutableStateOf(baseGraphicsDrivers.toMutableList()) }
        var graphicsDrivers by graphicsDriversRef
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
        val bionicWineEntriesRef = remember { mutableStateOf(bionicWineEntriesBase) }
        var bionicWineEntries by bionicWineEntriesRef
        val glibcWineEntriesRef = remember { mutableStateOf(glibcWineEntriesBase) }
        var glibcWineEntries by glibcWineEntriesRef
        val emulatorEntries = stringArrayResource(R.array.emulator_entries).toList()
        val bionicGraphicsDrivers = stringArrayResource(R.array.bionic_graphics_driver_entries).toList()
        val baseWrapperVersions = stringArrayResource(R.array.wrapper_graphics_driver_version_entries).toList()
        val wrapperVersionsRef = remember { mutableStateOf(baseWrapperVersions) }
        var wrapperVersions by wrapperVersionsRef
        val dxvkVersionsAllRef = remember { mutableStateOf(dxvkVersionsBase) }
        var dxvkVersionsAll by dxvkVersionsAllRef
        val componentAvailabilityRef = remember { mutableStateOf<ManifestComponentHelper.ComponentAvailability?>(null) }
        var componentAvailability by componentAvailabilityRef
        var manifestInstallInProgress by remember { mutableStateOf(false) }
        var showManifestDownloadDialog by remember { mutableStateOf(false) }
        var manifestDownloadProgress by remember { mutableStateOf(-1f) }
        var manifestDownloadLabel by remember { mutableStateOf("") }
        var versionsLoaded by remember { mutableStateOf(false) }
        val showCustomResolutionDialogRef = remember { mutableStateOf(false) }
        var showCustomResolutionDialog by showCustomResolutionDialogRef
        val customResolutionValidationErrorRef = remember { mutableStateOf<String?>(null) }
        var customResolutionValidationError by customResolutionValidationErrorRef

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
            manifestDxvk.associateBy { it.id }
        }
        val vkd3dManifestById = remember(manifestVkd3d) {
            manifestVkd3d.associateBy { it.id }
        }
        val box64ManifestById = remember(manifestBox64) {
            manifestBox64.associateBy { it.id }
        }
        val wowBox64ManifestById = remember(manifestWowBox64) {
            manifestWowBox64.associateBy { it.id }
        }
        val fexcoreManifestById = remember(manifestFexcore) {
            manifestFexcore.associateBy { it.id }
        }
        val wrapperManifestById = remember(manifestDrivers) {
            manifestDrivers.associateBy { it.id }
        }
        val bionicWineManifestById = remember(bionicWineManifest) {
            bionicWineManifest.associateBy { it.id }
        }
        val glibcWineManifestById = remember(glibcWineManifest) {
            glibcWineManifest.associateBy { it.id }
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
        val vkMaxVersionIndexRef = rememberSaveable { mutableIntStateOf(3) }
        var vkMaxVersionIndex by vkMaxVersionIndexRef
        val imageCacheIndexRef = rememberSaveable { mutableIntStateOf(2) }
        var imageCacheIndex by imageCacheIndexRef
        // Exposed device extensions selection indices; populated dynamically when UI opens
        val exposedExtIndicesRef = rememberSaveable { mutableStateOf(listOf<Int>()) }
        var exposedExtIndices by exposedExtIndicesRef
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
        val emulator64IndexRef = rememberSaveable {
            val idx = when {
                config.wineVersion.contains("x86_64", true) -> 1
                config.wineVersion.contains("arm64ec", true) -> 0
                else -> 0
            }
            mutableIntStateOf(idx)
        }
        var emulator64Index by emulator64IndexRef
        val emulator32IndexRef = rememberSaveable {
            val current = config.emulator.ifEmpty { Container.DEFAULT_EMULATOR }
            val idx = emulatorEntries.indexOfFirst { it.equals(current, true) }.coerceAtLeast(0)
            mutableIntStateOf(idx)
        }
        var emulator32Index by emulator32IndexRef

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
        val maxDeviceMemoryIndexRef = rememberSaveable { mutableIntStateOf(4) } // default 4096
        var maxDeviceMemoryIndex by maxDeviceMemoryIndexRef
        LaunchedEffect(config.graphicsDriverConfig) {
            val cfg = KeyValueSet(config.graphicsDriverConfig)
            val options = listOf("0", "512", "1024", "2048", "4096")
            val current = cfg.get("maxDeviceMemory", "4096")
            val found = options.indexOf(current)
            maxDeviceMemoryIndex = if (found >= 0) found else 4
        }

        // Bionic-specific state
        val bionicDriverIndexRef = rememberSaveable {
            val idx = bionicGraphicsDrivers.indexOfFirst { StringUtils.parseIdentifier(it) == config.graphicsDriver }
            mutableIntStateOf(if (idx >= 0) idx else 0)
        }
        var bionicDriverIndex by bionicDriverIndexRef
        val wrapperVersionIndexRef = rememberSaveable { mutableIntStateOf(0) }
        var wrapperVersionIndex by wrapperVersionIndexRef
        val presentModeIndexRef = rememberSaveable { mutableIntStateOf(0) }
        var presentModeIndex by presentModeIndexRef
        val resourceTypeIndexRef = rememberSaveable { mutableIntStateOf(0) }
        var resourceTypeIndex by resourceTypeIndexRef
        val bcnEmulationIndexRef = rememberSaveable { mutableIntStateOf(0) }
        var bcnEmulationIndex by bcnEmulationIndexRef
        val bcnEmulationTypeIndexRef = rememberSaveable { mutableIntStateOf(0) }
        var bcnEmulationTypeIndex by bcnEmulationTypeIndexRef
        val bcnEmulationCacheEnabledRef = rememberSaveable { mutableStateOf(false) }
        var bcnEmulationCacheEnabled by bcnEmulationCacheEnabledRef
        val disablePresentWaitCheckedRef = rememberSaveable { mutableStateOf(false) }
        var disablePresentWaitChecked by disablePresentWaitCheckedRef
        val syncEveryFrameCheckedRef = rememberSaveable { mutableStateOf(false) }
        var syncEveryFrameChecked by syncEveryFrameCheckedRef
        val sharpnessEffectIndexRef = rememberSaveable {
            val idx = sharpnessEffects.indexOfFirst { it.equals(config.sharpnessEffect, true) }.coerceAtLeast(0)
            mutableIntStateOf(idx)
        }
        var sharpnessEffectIndex by sharpnessEffectIndexRef
        val sharpnessLevelRef = rememberSaveable { mutableIntStateOf(config.sharpnessLevel.coerceIn(0, 100)) }
        var sharpnessLevel by sharpnessLevelRef
        val sharpnessDenoiseRef = rememberSaveable { mutableIntStateOf(config.sharpnessDenoise.coerceIn(0, 100)) }
        var sharpnessDenoise by sharpnessDenoiseRef
        val adrenotoolsTurnipCheckedRef = rememberSaveable {
            val cfg = KeyValueSet(config.graphicsDriverConfig)
            mutableStateOf(cfg.get("adrenotoolsTurnip", "1") != "0")
        }
        var adrenotoolsTurnipChecked by adrenotoolsTurnipCheckedRef
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

        val screenSizeIndexRef = rememberSaveable {
            val searchIndex = screenSizes.indexOfFirst { it.contains(config.screenSize) }
            mutableIntStateOf(if (searchIndex > 0) searchIndex else 0)
        }
        var screenSizeIndex by screenSizeIndexRef
        val customScreenWidthRef = rememberSaveable {
            val searchIndex = screenSizes.indexOfFirst { it.contains(config.screenSize) }
            mutableStateOf(
                if (searchIndex <= 0) config.screenSize.split("x").getOrElse(0) { "1280" } else "1280"
            )
        }
        var customScreenWidth by customScreenWidthRef
        val customScreenHeightRef = rememberSaveable {
            val searchIndex = screenSizes.indexOfFirst { it.contains(config.screenSize) }
            mutableStateOf(
                if (searchIndex <= 0) config.screenSize.split("x").getOrElse(1) { "720" } else "720"
            )
        }
        var customScreenHeight by customScreenHeightRef
        val graphicsDriverIndexRef = rememberSaveable {
            val driverIndex = graphicsDrivers.indexOfFirst { StringUtils.parseIdentifier(it) == config.graphicsDriver }
            mutableIntStateOf(if (driverIndex >= 0) driverIndex else 0)
        }
        var graphicsDriverIndex by graphicsDriverIndexRef

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
        val dxWrapperIndexRef = rememberSaveable {
            val driverIndex = dxWrappers.indexOfFirst { StringUtils.parseIdentifier(it) == config.dxwrapper }
            mutableIntStateOf(if (driverIndex >= 0) driverIndex else 0)
        }
        var dxWrapperIndex by dxWrapperIndexRef

        val dxvkVersionIndexRef = rememberSaveable { mutableIntStateOf(0) }
        var dxvkVersionIndex by dxvkVersionIndexRef

        // VKD3D version control (forced depending on driver)
        fun vkd3dForcedVersion(): String {
            val driverType = StringUtils.parseIdentifier(graphicsDrivers[graphicsDriverIndex])
            val isVortekLike = config.containerVariant.equals(Container.GLIBC) && driverType == "vortek" || driverType == "adreno" || driverType == "sd-8-elite"
            return if (isVortekLike) "2.6" else "2.14.1"
        }

        val graphicsDriverVersionIndexRef = rememberSaveable {
            val version = config.graphicsDriverVersion
            val driverIndex = if (version.isEmpty()) 0
            else getVersionsForDriver().indexOfFirst { it == version }.let { if (it >= 0) it else 0 }
            mutableIntStateOf(driverIndex)
        }
        var graphicsDriverVersionIndex by graphicsDriverVersionIndexRef
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
        val audioDriverIndexRef = rememberSaveable {
            val driverIndex = audioDrivers.indexOfFirst { StringUtils.parseIdentifier(it) == config.audioDriver }
            mutableIntStateOf(if (driverIndex >= 0) driverIndex else 0)
        }
        var audioDriverIndex by audioDriverIndexRef
        val gpuNameIndexRef = rememberSaveable {
            val gpuInfoIndex = gpuCards.values.indexOfFirst { it.deviceId == config.videoPciDeviceID }
            mutableIntStateOf(if (gpuInfoIndex >= 0) gpuInfoIndex else 0)
        }
        var gpuNameIndex by gpuNameIndexRef
        val renderingModeIndexRef = rememberSaveable {
            val index = renderingModes.indexOfFirst { it.lowercase() == config.offScreenRenderingMode }
            mutableIntStateOf(if (index >= 0) index else 0)
        }
        var renderingModeIndex by renderingModeIndexRef
        val videoMemIndexRef = rememberSaveable {
            val index = videoMemSizes.indexOfFirst { StringUtils.parseNumber(it) == config.videoMemorySize }
            mutableIntStateOf(if (index >= 0) index else 0)
        }
        var videoMemIndex by videoMemIndexRef
        val mouseWarpIndexRef = rememberSaveable {
            val index = mouseWarps.indexOfFirst { it.lowercase() == config.mouseWarpOverride }
            mutableIntStateOf(if (index >= 0) index else 0)
        }
        var mouseWarpIndex by mouseWarpIndexRef
        val externalDisplayModeIndexRef = rememberSaveable {
            val index = when (config.externalDisplayMode.lowercase()) {
                Container.EXTERNAL_DISPLAY_MODE_TOUCHPAD -> 1
                Container.EXTERNAL_DISPLAY_MODE_KEYBOARD -> 2
                Container.EXTERNAL_DISPLAY_MODE_HYBRID -> 3
                else -> 0
            }
            mutableIntStateOf(index)
        }
        var externalDisplayModeIndex by externalDisplayModeIndexRef
        val languageIndexRef = rememberSaveable {
            val idx = languages.indexOfFirst { it == config.language.lowercase() }
            mutableIntStateOf(if (idx >= 0) idx else languages.indexOf("english"))
        }
        var languageIndex by languageIndexRef

        var dismissDialogState by rememberSaveable(stateSaver = MessageDialogState.Saver) {
            mutableStateOf(MessageDialogState(visible = false))
        }
        val showEnvVarCreateDialogRef = rememberSaveable { mutableStateOf(false) }
        var showEnvVarCreateDialog by showEnvVarCreateDialogRef
        val showAddDriveDialogRef = rememberSaveable { mutableStateOf(false) }
        val selectedDriveLetterRef = rememberSaveable { mutableStateOf("") }
        var selectedDriveLetter by selectedDriveLetterRef
        val pendingDriveLetterRef = rememberSaveable { mutableStateOf("") }
        var pendingDriveLetter by pendingDriveLetterRef
        val driveLetterMenuExpandedRef = rememberSaveable { mutableStateOf(false) }
        var driveLetterMenuExpanded by driveLetterMenuExpandedRef

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

        val state = ContainerConfigState(
            config = configState,
            graphicsDrivers = graphicsDriversRef,
            bionicWineEntries = bionicWineEntriesRef,
            glibcWineEntries = glibcWineEntriesRef,
            wrapperVersions = wrapperVersionsRef,
            dxvkVersionsAll = dxvkVersionsAllRef,
            componentAvailability = componentAvailabilityRef,
            showCustomResolutionDialog = showCustomResolutionDialogRef,
            customResolutionValidationError = customResolutionValidationErrorRef,
            vkMaxVersionIndex = vkMaxVersionIndexRef,
            imageCacheIndex = imageCacheIndexRef,
            exposedExtIndices = exposedExtIndicesRef,
            maxDeviceMemoryIndex = maxDeviceMemoryIndexRef,
            bionicDriverIndex = bionicDriverIndexRef,
            wrapperVersionIndex = wrapperVersionIndexRef,
            presentModeIndex = presentModeIndexRef,
            resourceTypeIndex = resourceTypeIndexRef,
            bcnEmulationIndex = bcnEmulationIndexRef,
            bcnEmulationTypeIndex = bcnEmulationTypeIndexRef,
            bcnEmulationCacheEnabled = bcnEmulationCacheEnabledRef,
            disablePresentWaitChecked = disablePresentWaitCheckedRef,
            syncEveryFrameChecked = syncEveryFrameCheckedRef,
            sharpnessEffectIndex = sharpnessEffectIndexRef,
            sharpnessLevel = sharpnessLevelRef,
            sharpnessDenoise = sharpnessDenoiseRef,
            adrenotoolsTurnipChecked = adrenotoolsTurnipCheckedRef,
            emulator64Index = emulator64IndexRef,
            emulator32Index = emulator32IndexRef,
            screenSizeIndex = screenSizeIndexRef,
            customScreenWidth = customScreenWidthRef,
            customScreenHeight = customScreenHeightRef,
            graphicsDriverIndex = graphicsDriverIndexRef,
            dxWrapperIndex = dxWrapperIndexRef,
            dxvkVersionIndex = dxvkVersionIndexRef,
            graphicsDriverVersionIndex = graphicsDriverVersionIndexRef,
            audioDriverIndex = audioDriverIndexRef,
            gpuNameIndex = gpuNameIndexRef,
            renderingModeIndex = renderingModeIndexRef,
            videoMemIndex = videoMemIndexRef,
            mouseWarpIndex = mouseWarpIndexRef,
            externalDisplayModeIndex = externalDisplayModeIndexRef,
            languageIndex = languageIndexRef,
            showEnvVarCreateDialog = showEnvVarCreateDialogRef,
            showAddDriveDialog = showAddDriveDialogRef,
            selectedDriveLetter = selectedDriveLetterRef,
            pendingDriveLetter = pendingDriveLetterRef,
            driveLetterMenuExpanded = driveLetterMenuExpandedRef,
            screenSizes = screenSizes,
            baseGraphicsDrivers = baseGraphicsDrivers,
            dxWrappers = dxWrappers,
            dxvkVersionsBase = dxvkVersionsBase,
            vkd3dVersionsBase = vkd3dVersionsBase,
            audioDrivers = audioDrivers,
            presentModes = presentModes,
            resourceTypes = resourceTypes,
            bcnEmulationEntries = bcnEmulationEntries,
            bcnEmulationTypeEntries = bcnEmulationTypeEntries,
            sharpnessEffects = sharpnessEffects,
            sharpnessDisplayItems = sharpnessDisplayItems,
            renderingModes = renderingModes,
            videoMemSizes = videoMemSizes,
            mouseWarps = mouseWarps,
            externalDisplayModes = externalDisplayModes,
            winCompOpts = winCompOpts,
            box64Versions = box64Versions,
            wowBox64VersionsBase = wowBox64VersionsBase,
            box64BionicVersionsBase = box64BionicVersionsBase,
            fexcoreVersionsBase = fexcoreVersionsBase,
            fexcoreTSOPresets = fexcoreTSOPresets,
            fexcoreX87Presets = fexcoreX87Presets,
            fexcoreMultiblockValues = fexcoreMultiblockValues,
            startupSelectionEntries = startupSelectionEntries,
            turnipVersions = turnipVersions,
            virglVersions = virglVersions,
            zinkVersions = zinkVersions,
            vortekVersions = vortekVersions,
            adrenoVersions = adrenoVersions,
            sd8EliteVersions = sd8EliteVersions,
            containerVariants = containerVariants,
            bionicWineEntriesBase = bionicWineEntriesBase,
            glibcWineEntriesBase = glibcWineEntriesBase,
            emulatorEntries = emulatorEntries,
            bionicGraphicsDrivers = bionicGraphicsDrivers,
            baseWrapperVersions = baseWrapperVersions,
            languages = languages,
            dxvkOptions = dxvkOptions,
            vkd3dOptions = vkd3dOptions,
            box64Options = box64Options,
            box64BionicOptions = box64BionicOptions,
            wowBox64Options = wowBox64Options,
            fexcoreOptions = fexcoreOptions,
            wrapperOptions = wrapperOptions,
            bionicWineOptions = bionicWineOptions,
            glibcWineOptions = glibcWineOptions,
            dxvkManifestById = dxvkManifestById,
            vkd3dManifestById = vkd3dManifestById,
            box64ManifestById = box64ManifestById,
            wowBox64ManifestById = wowBox64ManifestById,
            fexcoreManifestById = fexcoreManifestById,
            wrapperManifestById = wrapperManifestById,
            bionicWineManifestById = bionicWineManifestById,
            glibcWineManifestById = glibcWineManifestById,
            gpuCards = gpuCards,
            box64Presets = box64Presets,
            fexcorePresets = fexcorePresets,
            gpuExtensions = gpuExtensions,
            inspectionMode = inspectionMode,
            isBionicVariant = isBionicVariant,
            nonDeletableDriveLetters = nonDeletableDriveLetters,
            availableDriveLetters = availableDriveLetters,
            launchManifestInstall = { entry, label, isDriver, expectedType, onInstalled ->
                launchManifestInstall(entry, label, isDriver, expectedType, onInstalled)
            },
            launchManifestContentInstall = { entry, expectedType, onInstalled ->
                launchManifestContentInstall(entry, expectedType, onInstalled)
            },
            launchManifestDriverInstall = { entry, onInstalled -> launchManifestDriverInstall(entry, onInstalled) },
            getStartupSelectionOptions = { getStartupSelectionOptions() },
            launchFolderPicker = {
                showAddDriveDialogRef.value = false
                pendingDriveLetterRef.value = selectedDriveLetterRef.value
                SteamService.keepAlive = true
                folderPicker.launchPicker()
            },
            getVersionsForDriver = { getVersionsForDriver() },
            getVersionsForBox64 = { getVersionsForBox64() },
            applyScreenSizeToConfig = applyScreenSizeToConfig,
            vkd3dForcedVersion = { vkd3dForcedVersion() },
            currentDxvkContext = { currentDxvkContext() },
        )

        LoadingDialog(
            visible = showManifestDownloadDialog,
            progress = manifestDownloadProgress,
            message = manifestDownloadMessage,
        )

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
                            if (selectedTab == 0) GeneralTabContent(state, nonzeroResolutionError, aspectResolutionError)
                            if (selectedTab == 1) GraphicsTabContent(state)
                            if (selectedTab == 2) EmulationTabContent(state)
                            if (selectedTab == 3) ControllerTabContent(state, default)
                            if (selectedTab == 4) WineTabContent(state)
                            if (selectedTab == 5) WinComponentsTabContent(state)
                            if (selectedTab == 6) EnvironmentTabContent(state)
                            if (selectedTab == 7) DrivesTabContent(state)
                            if (selectedTab == 8) AdvancedTabContent(state)
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
internal fun ExecutablePathDropdown(
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
