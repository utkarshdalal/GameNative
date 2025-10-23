package app.gamenative.ui.component.dialog

import android.widget.Toast
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.content.res.Configuration
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.theme.settingsTileColorsAlt
import app.gamenative.utils.ContainerUtils
import app.gamenative.service.SteamService
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
import java.util.Locale

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

        var config by rememberSaveable(stateSaver = ContainerData.Saver) {
            mutableStateOf(initialConfig)
        }

        val screenSizes = stringArrayResource(R.array.screen_size_entries).toList()
        val graphicsDrivers = stringArrayResource(R.array.graphics_driver_entries).toList()
        val dxWrappers = stringArrayResource(R.array.dxwrapper_entries).toList()
        val dxvkVersionsAll = stringArrayResource(R.array.dxvk_version_entries).toList()
        val vkd3dVersions = stringArrayResource(R.array.vkd3d_version_entries).toList()
        val audioDrivers = stringArrayResource(R.array.audio_driver_entries).toList()
        val gpuCards = ContainerUtils.getGPUCards(context)
        val renderingModes = stringArrayResource(R.array.offscreen_rendering_modes).toList()
        val videoMemSizes = stringArrayResource(R.array.video_memory_size_entries).toList()
        val mouseWarps = stringArrayResource(R.array.mouse_warp_override_entries).toList()
        val winCompOpts = stringArrayResource(R.array.win_component_entries).toList()
        val box64Versions = stringArrayResource(R.array.box64_version_entries).toList()
        val wowBox64Versions = stringArrayResource(R.array.wowbox64_version_entries).toList()
        val box64BionicVersions = stringArrayResource(R.array.box64_bionic_version_entries).toList()
        val box64Presets = Box86_64PresetManager.getPresets("box64", context)
        val fexcoreVersions = stringArrayResource(R.array.fexcore_version_entries).toList()
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
        val bionicWineEntries = stringArrayResource(R.array.bionic_wine_entries).toList()
        val glibcWineEntries = stringArrayResource(R.array.glibc_wine_entries).toList()
        val emulatorEntries = stringArrayResource(R.array.emulator_entries).toList()
        val bionicGraphicsDrivers = stringArrayResource(R.array.bionic_graphics_driver_entries).toList()
        val wrapperVersions = stringArrayResource(R.array.wrapper_graphics_driver_version_entries).toList()
        val frameSyncEntries = stringArrayResource(R.array.frame_sync_entries).toList()
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
        var wrapperVersionIndex by rememberSaveable {
            val cfg = KeyValueSet(config.graphicsDriverConfig)
            val selected = cfg.get("version", DefaultVersion.WRAPPER)
            val idx = wrapperVersions.indexOfFirst { it == selected }
            mutableIntStateOf(if (idx >= 0) idx else 0)
        }
        var frameSyncIndex by rememberSaveable {
            val cfg = KeyValueSet(config.graphicsDriverConfig)
            val selected = cfg.get("frameSync", "Normal")
            val idx = frameSyncEntries.indexOfFirst { it.equals(selected, ignoreCase = true) }
            mutableIntStateOf(if (idx >= 0) idx else frameSyncEntries.indexOf("Normal").coerceAtLeast(0))
        }
        var adrenotoolsTurnipChecked by rememberSaveable {
            val cfg = KeyValueSet(config.graphicsDriverConfig)
            mutableStateOf(cfg.get("adrenotoolsTurnip", "1") != "0")
        }
        LaunchedEffect(config.graphicsDriverConfig) {
            val cfg = KeyValueSet(config.graphicsDriverConfig)
            val ver = cfg.get("version", DefaultVersion.WRAPPER)
            wrapperVersionIndex = wrapperVersions.indexOfFirst { it == ver }.coerceAtLeast(0)
            val fs = cfg.get("frameSync", "Normal")
            frameSyncIndex = frameSyncEntries.indexOfFirst { it.equals(fs, true) }.let { if (it >= 0) it else frameSyncEntries.indexOf("Normal").coerceAtLeast(0) }
            adrenotoolsTurnipChecked = cfg.get("adrenotoolsTurnip", "1") != "0"
        }

        var screenSizeIndex by rememberSaveable {
            val searchIndex = screenSizes.indexOfFirst { it.contains(config.screenSize) }
            mutableIntStateOf(if (searchIndex > 0) searchIndex else 0)
        }
        var customScreenWidth by rememberSaveable {
            val searchIndex = screenSizes.indexOfFirst { it.contains(config.screenSize) }
            mutableStateOf(if (searchIndex <= 0) config.screenSize.split("x")[0] else "")
        }
        var customScreenHeight by rememberSaveable {
            val searchIndex = screenSizes.indexOfFirst { it.contains(config.screenSize) }
            mutableStateOf(if (searchIndex <= 0) config.screenSize.split("x")[1] else "")
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

        fun getVersionsForBox64(): List<String> {
            if (config.wineVersion.equals(MAIN_WINE_VERSION.identifier())) {
                return box64Versions
            } else if (config.wineVersion.contains("x86_64", true)) {
                return box64BionicVersions
            } else if (config.wineVersion.contains("arm64ec", true)) {
                return wowBox64Versions
            }
            return box64Versions
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
        var dxWrapperIndex by rememberSaveable {
            val driverIndex = dxWrappers.indexOfFirst { StringUtils.parseIdentifier(it) == config.dxwrapper }
            mutableIntStateOf(if (driverIndex >= 0) driverIndex else 0)
        }

        fun currentDxvkContext(): Pair<Boolean, List<String>> {
            val driverType    = StringUtils.parseIdentifier(graphicsDrivers[graphicsDriverIndex])
            val isVortekLike  = driverType in listOf("vortek", "adreno", "sd-8-elite")

            val isVKD3D       = StringUtils.parseIdentifier(dxWrappers[dxWrapperIndex]) == "vkd3d"
            val constrained   = if (!inspectionMode && isVortekLike && GPUHelper.vkGetApiVersion() < GPUHelper.vkMakeVersion(1, 3, 0))
                listOf("1.10.3", "1.10.9-sarek", "1.9.2", "async-1.10.3")
            else
                dxvkVersionsAll

            val effectiveList = if (isVKD3D) emptyList() else constrained
            return isVortekLike to effectiveList
        }
        // VKD3D version control (forced depending on driver)
        fun vkd3dForcedVersion(): String {
            val driverType = StringUtils.parseIdentifier(graphicsDrivers[graphicsDriverIndex])
            val isVortekLike = driverType == "vortek" || driverType == "adreno" || driverType == "sd-8-elite"
            return if (isVortekLike) "2.6" else "2.14.1"
        }
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
        var dxvkVersionIndex by rememberSaveable {
            val rawConfig = config.dxwrapperConfig
            val kvs = KeyValueSet(rawConfig)

            val configuredVersion = kvs.get("version") // Direct call to get()

            val (_, effectiveList) = currentDxvkContext()

            // Find index where the parsed display string matches the configured version
            val foundIndex = effectiveList.indexOfFirst {
                val parsedDisplay = StringUtils.parseIdentifier(it)
                val match = parsedDisplay == configuredVersion
                match
            }

            // Use found index, or fallback to the app's default DXVK version, or 0 if not found
            val defaultVersion = DefaultVersion.DXVK
            val defaultIndex = effectiveList.indexOfFirst {
                StringUtils.parseIdentifier(it) == defaultVersion
            }.coerceAtLeast(0)
            val finalIndex = if (foundIndex >= 0) foundIndex else defaultIndex
            mutableIntStateOf(finalIndex)
        }
        // When DXVK version defaults to an 'async' build, enable DXVK_ASYNC by default
        LaunchedEffect(dxvkVersionIndex, graphicsDriverIndex, dxWrapperIndex) {
            val (isVortekLike, effectiveList) = currentDxvkContext()
            if (dxvkVersionIndex !in effectiveList.indices) dxvkVersionIndex = 0

            // Ensure index within range or default
            val selectedDisplay = effectiveList.getOrNull(dxvkVersionIndex)
            val selectedVersion = StringUtils.parseIdentifier(selectedDisplay ?: "")
            val version = if (selectedVersion.isEmpty()) {
                if (isVortekLike) "async-1.10.3" else StringUtils.parseIdentifier(dxvkVersionsAll.getOrNull(dxvkVersionIndex) ?: DefaultVersion.DXVK)
            } else selectedVersion
            val envSet = EnvVars(config.envVars)
            if (version.contains("async", ignoreCase = true)) {
                envSet.put("DXVK_ASYNC", "1")
            } else {
                envSet.remove("DXVK_ASYNC")
            }
            // Update dxwrapperConfig version only when DXVK wrapper selected
            val wrapperIsDxvk = StringUtils.parseIdentifier(dxWrappers[dxWrapperIndex]) == "dxvk"
            val kvs = KeyValueSet(config.dxwrapperConfig)
            if (wrapperIsDxvk) {
                kvs.put("version", version)
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
        var languageIndex by rememberSaveable {
            val idx = languages.indexOfFirst { it == config.language.lowercase() }
            mutableIntStateOf(if (idx >= 0) idx else languages.indexOf("english"))
        }

        var dismissDialogState by rememberSaveable(stateSaver = MessageDialogState.Saver) {
            mutableStateOf(MessageDialogState(visible = false))
        }
        var showEnvVarCreateDialog by rememberSaveable { mutableStateOf(false) }

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
                    title = "Unsaved Changes",
                    message = "Are you sure you'd like to discard your changes?",
                    confirmBtnText = "Discard",
                    dismissBtnText = "Cancel",
                )
            } else {
                onDismissRequest()
            }
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
                title = { Text(text = "New Environment Variable") },
                text = {
                    var knownVarsMenuOpen by rememberSaveable { mutableStateOf(false) }
                    Column {
                        Row {
                            OutlinedTextField(
                                value = envVarName,
                                onValueChange = { envVarName = it },
                                label = { Text(text = "Name") },
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
                                        text = { Text(text = "No more known variables") },
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
                                title = { Text(text = "Value") },
                                colors = settingsTileColors(),
                            )
                        } else {
                            OutlinedTextField(
                                value = envVarValue,
                                onValueChange = { envVarValue = it },
                                label = { Text(text = "Value") },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showEnvVarCreateDialog = false },
                        content = { Text(text = "Cancel") },
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
                        content = { Text(text = "OK") },
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
                    val tabs = listOf("General", "Graphics", "Emulation", "Controller", "Wine", "Win Components", "Environment", "Drives", "Advanced")
                    Column(
                        modifier = Modifier
                            .padding(
                                top = WindowInsets.statusBars
                                    .asPaddingValues()
                                    .calculateTopPadding() + paddingValues.calculateTopPadding(),
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
                                        title = { Text(text = "Container Variant") },
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
                                                    put("frameSync", "Normal")
                                                    put("adrenotoolsTurnip", "1")
                                                }
                                                graphicsDriverIndex =
                                                    graphicsDrivers.indexOfFirst { StringUtils.parseIdentifier(it) == defaultDriver }
                                                        .coerceAtLeast(0)
                                                graphicsDriverVersionIndex = 0
                                                frameSyncIndex = frameSyncEntries.indexOf("Normal").coerceAtLeast(0)
                                                adrenotoolsTurnipChecked = true

                                                config = config.copy(
                                                    containerVariant = newVariant,
                                                    wineVersion = glibcWineEntries.first(),
                                                    graphicsDriver = defaultDriver,
                                                    graphicsDriverVersion = "",
                                                    graphicsDriverConfig = newCfg.toString(),
                                                )
                                            } else {
                                                // Switch to bionic: set wrapper defaults
                                                val defaultBionicDriver = StringUtils.parseIdentifier(bionicGraphicsDrivers.first())
                                                val newWine =
                                                    if (config.wineVersion == glibcWineEntries.first()) bionicWineEntries.firstOrNull()
                                                        ?: config.wineVersion else config.wineVersion
                                                val newCfg = KeyValueSet(config.graphicsDriverConfig).apply {
                                                    put("version", DefaultVersion.WRAPPER)
                                                    put("frameSync", "Normal")
                                                    put("adrenotoolsTurnip", "1")
                                                    if (get("exposedDeviceExtensions").isEmpty()) put("exposedDeviceExtensions", "all")
                                                    if (get("maxDeviceMemory").isEmpty()) put("maxDeviceMemory", "4096")
                                                }
                                                bionicDriverIndex = 0
                                                wrapperVersionIndex = wrapperVersions.indexOfFirst { it == DefaultVersion.WRAPPER }
                                                    .let { if (it >= 0) it else 0 }
                                                frameSyncIndex = frameSyncEntries.indexOf("Normal").coerceAtLeast(0)
                                                adrenotoolsTurnipChecked = true
                                                maxDeviceMemoryIndex =
                                                    listOf("0", "512", "1024", "2048", "4096").indexOf("4096").coerceAtLeast(0)

                                                config = config.copy(
                                                    containerVariant = newVariant,
                                                    wineVersion = newWine,
                                                    graphicsDriver = defaultBionicDriver,
                                                    graphicsDriverVersion = "",
                                                    graphicsDriverConfig = newCfg.toString(),
                                                )
                                            }
                                        },
                                    )
                                    // Wine version only if bionic variant
                                    if (config.containerVariant.equals(Container.BIONIC, ignoreCase = true)) {
                                        val wineIndex = bionicWineEntries.indexOfFirst { it == config.wineVersion }.coerceAtLeast(0)
                                        SettingsListDropdown(
                                            colors = settingsTileColors(),
                                            title = { Text(text = "Wine Version") },
                                            value = wineIndex,
                                            items = bionicWineEntries,
                                            onItemSelected = { idx ->
                                                config = config.copy(wineVersion = bionicWineEntries[idx])
                                            },
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                    value = config.executablePath,
                                    onValueChange = { config = config.copy(executablePath = it) },
                                    label = { Text(text = "Executable Path") },
                                    placeholder = { Text(text = "e.g., path\\to\\exe") },
                                )
                                OutlinedTextField(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                    value = config.execArgs,
                                    onValueChange = { config = config.copy(execArgs = it) },
                                    label = { Text(text = "Exec Arguments") },
                                    placeholder = { Text(text = "Example: -dx11") },
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
                                    title = { Text(text = "Language") },
                                    colors = settingsTileColors(),
                                )
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(text = "Screen Size") },
                                    value = screenSizeIndex,
                                    items = screenSizes,
                                    onItemSelected = {
                                        screenSizeIndex = it
                                        applyScreenSizeToConfig()
                                    },
                                    action = if (screenSizeIndex == 0) {
                                        {
                                            Row {
                                                OutlinedTextField(
                                                    modifier = Modifier.width(128.dp),
                                                    value = customScreenWidth,
                                                    onValueChange = {
                                                        customScreenWidth = it
                                                        applyScreenSizeToConfig()
                                                    },
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                    label = { Text(text = "Width") },
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    modifier = Modifier.align(Alignment.CenterVertically),
                                                    text = "x",
                                                    style = TextStyle(fontSize = 16.sp),
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                OutlinedTextField(
                                                    modifier = Modifier.width(128.dp),
                                                    value = customScreenHeight,
                                                    onValueChange = {
                                                        customScreenHeight = it
                                                        applyScreenSizeToConfig()
                                                    },
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                    label = { Text(text = "Height") },
                                                )
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                )
                                // Audio Driver Dropdown
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(text = "Audio Driver") },
                                    value = audioDriverIndex,
                                    items = audioDrivers,
                                    onItemSelected = {
                                        audioDriverIndex = it
                                        config = config.copy(audioDriver = StringUtils.parseIdentifier(audioDrivers[it]))
                                    },
                                )
                                SettingsSwitch(
                                    colors = settingsTileColorsAlt(),
                                    title = { Text(text = "Show FPS") },
                                    state = config.showFPS,
                                    onCheckedChange = {
                                        config = config.copy(showFPS = it)
                                    },
                                )
                                SettingsSwitch(
                                    colors = settingsTileColorsAlt(),
                                    title = { Text(text = "Launch Steam Client (Beta)") },
                                    subtitle = { Text(text = "Reduces performance and slows down launch\nAllows online play and fixes DRM and controller issues\nNot all games work") },
                                    state = config.launchRealSteam,
                                    onCheckedChange = {
                                        config = config.copy(launchRealSteam = it)
                                    },
                                )
                                if (config.launchRealSteam) {
                                    SettingsSwitch(
                                        colors = settingsTileColorsAlt(),
                                        title = { Text(text = "Allow Steam updates") },
                                        subtitle = { Text(text = "Updates Steam to the latest version. Significantly reduces performance.") },
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
                                    title = { Text(text = "Steam Type") },
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
                                        title = { Text(text = "Graphics Driver") },
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
                                        title = { Text(text = "Graphics Driver Version") },
                                        value = wrapperVersionIndex,
                                        items = wrapperVersions,
                                        onItemSelected = { idx ->
                                            wrapperVersionIndex = idx
                                            val cfg = KeyValueSet(config.graphicsDriverConfig)
                                            cfg.put("version", wrapperVersions[idx])
                                            config = config.copy(graphicsDriverConfig = cfg.toString())
                                        },
                                    )
                                    // Bionic: Exposed Vulkan Extensions (same UI as Vortek)
                                    SettingsMultiListDropdown(
                                        colors = settingsTileColors(),
                                        title = { Text(text = "Exposed Vulkan Extensions") },
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
                                            title = { Text(text = "Max Device Memory") },
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
                                    // Bionic: Frame Synchronization
                                    SettingsListDropdown(
                                        colors = settingsTileColors(),
                                        title = { Text(text = "Frame Synchronization") },
                                        value = frameSyncIndex,
                                        items = frameSyncEntries,
                                        onItemSelected = { idx ->
                                            frameSyncIndex = idx
                                            val cfg = KeyValueSet(config.graphicsDriverConfig)
                                            cfg.put("frameSync", frameSyncEntries[idx])
                                            config = config.copy(graphicsDriverConfig = cfg.toString())
                                        },
                                    )
                                    // Bionic: Use Adrenotools Turnip
                                    SettingsSwitch(
                                        colors = settingsTileColorsAlt(),
                                        title = { Text(text = "Use Adrenotools Turnip") },
                                        state = adrenotoolsTurnipChecked,
                                        onCheckedChange = { checked ->
                                            adrenotoolsTurnipChecked = checked
                                            val cfg = KeyValueSet(config.graphicsDriverConfig)
                                            cfg.put("adrenotoolsTurnip", if (checked) "1" else "0")
                                            config = config.copy(graphicsDriverConfig = cfg.toString())
                                        },
                                    )
                                } else {
                                    // Non-bionic: existing driver/version UI and Vortek-specific options
                                    SettingsListDropdown(
                                        colors = settingsTileColors(),
                                        title = { Text(text = "Graphics Driver") },
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
                                        title = { Text(text = "Graphics Driver Version") },
                                        value = graphicsDriverVersionIndex,
                                        items = getVersionsForDriver(),
                                        onItemSelected = {
                                            graphicsDriverVersionIndex = it
                                            val selectedVersion = if (it == 0) "" else getVersionsForDriver()[it]
                                            config = config.copy(graphicsDriverVersion = selectedVersion)
                                        },
                                    )
                                    // Vortek/Adreno specific settings
                                    run {
                                        val driverType = StringUtils.parseIdentifier(graphicsDrivers[graphicsDriverIndex])
                                        val isVortekLike = driverType == "vortek" || driverType == "adreno" || driverType == "sd-8-elite"
                                        if (isVortekLike) {
                                            // Vulkan Max Version
                                            val vkVersions = listOf("1.0", "1.1", "1.2", "1.3")
                                            SettingsListDropdown(
                                                colors = settingsTileColors(),
                                                title = { Text(text = "Vulkan Version") },
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
                                                title = { Text(text = "Exposed Vulkan Extensions") },
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
                                                title = { Text(text = "Image Cache Size") },
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
                                                title = { Text(text = "Max Device Memory") },
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
                                // TODO: add way to pick DXVK version
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(text = "DX Wrapper") },
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
                                    val isVortekLike = driverType == "vortek" || driverType == "adreno" || driverType == "sd-8-elite"
                                    val isVKD3D = StringUtils.parseIdentifier(dxWrappers[dxWrapperIndex]) == "vkd3d"
                                    val items =
                                        if (!inspectionMode && isVortekLike && GPUHelper.vkGetApiVersion() < GPUHelper.vkMakeVersion(
                                                1,
                                                3,
                                                0
                                            )
                                        ) listOf("1.10.3", "1.10.9-sarek", "1.9.2", "async-1.10.3") else dxvkVersionsAll
                                    if (!isVKD3D) {
                                        SettingsListDropdown(
                                            colors = settingsTileColors(),
                                            title = { Text(text = stringResource(R.string.dxvk_version)) },
                                            value = dxvkVersionIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                                            items = items,
                                            onItemSelected = {
                                                dxvkVersionIndex = it
                                                val version = StringUtils.parseIdentifier(items[it])
                                                val currentConfig = KeyValueSet(config.dxwrapperConfig)
                                                currentConfig.put("version", version)
                                                val envVarsSet = EnvVars(config.envVars)
                                                if (version.contains("async", ignoreCase = true)) envVarsSet.put(
                                                    "DXVK_ASYNC",
                                                    "1"
                                                ) else envVarsSet.remove("DXVK_ASYNC")
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
                                        val availableVersions = vkd3dVersions
                                        val selectedVersion =
                                            KeyValueSet(config.dxwrapperConfig).get("vkd3dVersion").ifEmpty { vkd3dForcedVersion() }
                                        val selectedIndex = availableVersions.indexOf(selectedVersion).coerceAtLeast(0)

                                        SettingsListDropdown(
                                            colors = settingsTileColors(),
                                            title = { Text(text = label) },
                                            value = selectedIndex,
                                            items = availableVersions,
                                            onItemSelected = { idx ->
                                                val currentConfig = KeyValueSet(config.dxwrapperConfig)
                                                currentConfig.put("vkd3dVersion", availableVersions[idx])
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
                                            title = { Text(text = "VKD3D Feature Level") },
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
                                SettingsSwitch(
                                    colors = settingsTileColorsAlt(),
                                    title = { Text(text = "Use DRI3") },
                                    subtitle = { Text(text = "Disabling may fix graphical glitches on some devices") },
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
                                                SettingsListDropdown(
                                                    colors = settingsTileColors(),
                                                    title = { Text(text = "FEXCore Version") },
                                                    value = fexcoreVersions.indexOfFirst { it == config.fexcoreVersion }.coerceAtLeast(0),
                                                    items = fexcoreVersions,
                                                    onItemSelected = { idx ->
                                                        config = config.copy(fexcoreVersion = fexcoreVersions[idx])
                                                    },
                                                )
                                                SettingsListDropdown(
                                                    colors = settingsTileColors(),
                                                    title = { Text(text = "TSO Mode") },
                                                    value = fexcoreTSOPresets.indexOfFirst { it == config.fexcoreTSOMode }.coerceAtLeast(0),
                                                    items = fexcoreTSOPresets,
                                                    onItemSelected = { idx ->
                                                        config = config.copy(fexcoreTSOMode = fexcoreTSOPresets[idx])
                                                    },
                                                )
                                                SettingsListDropdown(
                                                    colors = settingsTileColors(),
                                                    title = { Text(text = "x87 Mode") },
                                                    value = fexcoreX87Presets.indexOfFirst { it == config.fexcoreX87Mode }.coerceAtLeast(0),
                                                    items = fexcoreX87Presets,
                                                    onItemSelected = { idx ->
                                                        config = config.copy(fexcoreX87Mode = fexcoreX87Presets[idx])
                                                    },
                                                )
                                                SettingsListDropdown(
                                                    colors = settingsTileColors(),
                                                    title = { Text(text = "Multiblock") },
                                                    value = fexcoreMultiblockValues.indexOfFirst { it == config.fexcoreMultiBlock }
                                                        .coerceAtLeast(0),
                                                    items = fexcoreMultiblockValues,
                                                    onItemSelected = { idx ->
                                                        config = config.copy(fexcoreMultiBlock = fexcoreMultiblockValues[idx])
                                                    },
                                                )
                                            }
                                        }
                                    }

                                    // 64-bit Emulator (locked based on wine arch)
                                    SettingsListDropdown(
                                        colors = settingsTileColors(),
                                        title = { Text(text = "64-bit Emulator") },
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
                                        title = { Text(text = "32-bit Emulator") },
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
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(text = "Box64 Version") },
                                    value = getVersionsForBox64().indexOfFirst { StringUtils.parseIdentifier(it) == config.box64Version },
                                    items = getVersionsForBox64(),
                                    onItemSelected = {
                                        config = config.copy(
                                            box64Version = StringUtils.parseIdentifier(getVersionsForBox64()[it]),
                                        )
                                    },
                                )
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(text = "Box64 Preset") },
                                    value = box64Presets.indexOfFirst { it.id == config.box64Preset },
                                    items = box64Presets.map { it.name },
                                    onItemSelected = {
                                        config = config.copy(
                                            box64Preset = box64Presets[it].id,
                                        )
                                    },
                                )
                            }
                            if (selectedTab == 3) SettingsGroup() {
                                if (!default) {
                                    SettingsSwitch(
                                        colors = settingsTileColorsAlt(),
                                        title = { Text(text = "Use SDL API") },
                                        state = config.sdlControllerAPI,
                                        onCheckedChange = {
                                            config = config.copy(sdlControllerAPI = it)
                                        },
                                    )
                                }
                                // Enable XInput API
                                SettingsSwitch(
                                    colors = settingsTileColorsAlt(),
                                    title = { Text(text = "Enable XInput API") },
                                    state = config.enableXInput,
                                    onCheckedChange = {
                                        config = config.copy(enableXInput = it)
                                    }
                                )
                                // Enable DirectInput API
                                SettingsSwitch(
                                    colors = settingsTileColorsAlt(),
                                    title = { Text(text = "Enable DirectInput API") },
                                    state = config.enableDInput,
                                    onCheckedChange = {
                                        config = config.copy(enableDInput = it)
                                    }
                                )
                                // DirectInput Mapper Type
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(text = "DirectInput Mapper Type") },
                                    value = if (config.dinputMapperType == 1.toByte()) 0 else 1,
                                    items = listOf("Standard", "XInput Mapper"),
                                    onItemSelected = { index ->
                                        config = config.copy(dinputMapperType = if (index == 0) 1 else 2)
                                    }
                                )
                                // Disable external mouse input
                                SettingsSwitch(
                                    colors = settingsTileColorsAlt(),
                                    title = { Text(text = "Disable Mouse Input") },
                                    state = config.disableMouseInput,
                                    onCheckedChange = { config = config.copy(disableMouseInput = it) }
                                )

                                // Touchscreen mode
                                SettingsSwitch(
                                    colors = settingsTileColorsAlt(),
                                    title = { Text(text = "Touchscreen Mode") },
                                    state = config.touchscreenMode,
                                    onCheckedChange = { config = config.copy(touchscreenMode = it) }
                                )

                                // Emulate keyboard and mouse
                                SettingsSwitch(
                                    colors = settingsTileColorsAlt(),
                                    title = { Text(text = "Emulate keyboard and mouse") },
                                    subtitle = { Text(text = "Left stick = WASD, Right stick = mouse. L2 = left click, R2 = right click.") },
                                    state = config.emulateKeyboardMouse,
                                    onCheckedChange = { checked ->
                                        // Initialize defaults on first enable if empty
                                        var newBindings = config.controllerEmulationBindings
                                        if (checked && newBindings.isEmpty()) {
                                            newBindings = """
                                            {"L2":"MOUSE_LEFT_BUTTON","R2":"MOUSE_RIGHT_BUTTON","A":"KEY_SPACE","B":"KEY_Q","X":"KEY_E","Y":"KEY_TAB","SELECT":"KEY_ESC","L1":"KEY_SHIFT_L","L3":"NONE","R1":"KEY_CTRL_R","R3":"NONE","DPAD_UP":"KEY_UP","DPAD_DOWN":"KEY_DOWN","DPAD_LEFT":"KEY_LEFT","DPAD_RIGHT":"KEY_RIGHT","START":"KEY_ENTER"}
                                        """.trimIndent()
                                        }
                                        config = config.copy(emulateKeyboardMouse = checked, controllerEmulationBindings = newBindings)
                                    }
                                )

                                if (config.emulateKeyboardMouse) {
                                    // Dropdowns for mapping buttons -> bindings
                                    val buttonOrder = listOf(
                                        "A", "B", "X", "Y", "L1", "L2", "L3", "R1", "R2", "R3",
                                        "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "START", "SELECT"
                                    )
                                    val context = LocalContext.current
                                    val currentMap = try {
                                        org.json.JSONObject(config.controllerEmulationBindings)
                                    } catch (_: Exception) {
                                        org.json.JSONObject()
                                    }
                                    val bindingLabels = com.winlator.inputcontrols.Binding.keyboardBindingLabels().toList() +
                                            com.winlator.inputcontrols.Binding.mouseBindingLabels().toList()
                                    val bindingValues =
                                        com.winlator.inputcontrols.Binding.keyboardBindingValues().map { it.name }.toList() +
                                                com.winlator.inputcontrols.Binding.mouseBindingValues().map { it.name }.toList()

                                    for (btn in buttonOrder) {
                                        val currentName = currentMap.optString(btn, "NONE")
                                        val currentIndex = bindingValues.indexOf(currentName).coerceAtLeast(0)
                                        SettingsListDropdown(
                                            colors = settingsTileColors(),
                                            title = { Text(text = btn.replace('_', ' ')) },
                                            value = currentIndex,
                                            items = bindingLabels,
                                            onItemSelected = { idx ->
                                                try {
                                                    currentMap.put(btn, bindingValues[idx])
                                                    config = config.copy(controllerEmulationBindings = currentMap.toString())
                                                } catch (_: Exception) {
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                            if (selectedTab == 4) SettingsGroup() {
                                // TODO: add desktop settings
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(text = "Renderer") },
                                    value = gpuNameIndex,
                                    items = gpuCards.values.map { it.name },
                                    onItemSelected = {
                                        gpuNameIndex = it
                                        config = config.copy(videoPciDeviceID = gpuCards.values.toList()[it].deviceId)
                                    },
                                )
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(text = "GPU Name") },
                                    value = gpuNameIndex,
                                    items = gpuCards.values.map { it.name },
                                    onItemSelected = {
                                        gpuNameIndex = it
                                        config = config.copy(videoPciDeviceID = gpuCards.values.toList()[it].deviceId)
                                    },
                                )
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(text = "Offscreen Rendering Mode") },
                                    value = renderingModeIndex,
                                    items = renderingModes,
                                    onItemSelected = {
                                        renderingModeIndex = it
                                        config = config.copy(offScreenRenderingMode = renderingModes[it].lowercase())
                                    },
                                )
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(text = "Video Memory Size") },
                                    value = videoMemIndex,
                                    items = videoMemSizes,
                                    onItemSelected = {
                                        videoMemIndex = it
                                        config = config.copy(videoMemorySize = StringUtils.parseNumber(videoMemSizes[it]))
                                    },
                                )
                                SettingsSwitch(
                                    colors = settingsTileColorsAlt(),
                                    title = { Text(text = "Enable CSMT (Command Stream Multi-Thread)") },
                                    state = config.csmt,
                                    onCheckedChange = {
                                        config = config.copy(csmt = it)
                                    },
                                )
                                SettingsSwitch(
                                    colors = settingsTileColorsAlt(),
                                    title = { Text(text = "Enable Strict Shader Math") },
                                    state = config.strictShaderMath,
                                    onCheckedChange = {
                                        config = config.copy(strictShaderMath = it)
                                    },
                                )
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(text = "Mouse Warp Override") },
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
                                        title = { Text(text = "No environment variables") },
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
                                            // action = {
                                            //     IconButton(
                                            //         onClick = {
                                            //             config = config.copy(
                                            //                 drives = config.drives.replace("$driveLetter:$drivePath", ""),
                                            //             )
                                            //         },
                                            //         content = { Icon(Icons.Filled.Delete, contentDescription = "Delete drive") },
                                            //     )
                                            // },
                                        )
                                    }
                                } else {
                                    SettingsCenteredLabel(
                                        colors = settingsTileColors(),
                                        title = { Text(text = "No drives") },
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
                                        // TODO: add way to create new drive
                                        // directoryLauncher.launch(null)
                                        Toast.makeText(context, "Adding drives not yet available", Toast.LENGTH_LONG).show()
                                    },
                                )
                            }
                            if (selectedTab == 8) SettingsGroup() {
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(text = "Startup Selection") },
                                    value = config.startupSelection.toInt(),
                                    items = startupSelectionEntries,
                                    onItemSelected = {
                                        config = config.copy(
                                            startupSelection = it.toByte(),
                                        )
                                    },
                                )
                                SettingsCPUList(
                                    colors = settingsTileColors(),
                                    title = { Text(text = "Processor Affinity") },
                                    value = config.cpuList,
                                    onValueChange = {
                                        config = config.copy(
                                            cpuList = it,
                                        )
                                    },
                                )
                                SettingsCPUList(
                                    colors = settingsTileColors(),
                                    title = { Text(text = "Processor Affinity (32-bit apps)") },
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
            title = "Configure Container",
            initialConfig = previewConfig,
            onDismissRequest = {},
            onSave = {},
        )
    }
}
