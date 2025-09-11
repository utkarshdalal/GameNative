package app.gamenative.ui.component.dialog

import android.widget.Toast
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
import app.gamenative.R
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.ui.component.settings.SettingsCPUList
import app.gamenative.ui.component.settings.SettingsCenteredLabel
import app.gamenative.ui.component.settings.SettingsEnvVars
import app.gamenative.ui.component.settings.SettingsListDropdown
import app.gamenative.ui.component.settings.SettingsMultiListDropdown
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.theme.settingsTileColorsAlt
import app.gamenative.utils.ContainerUtils
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
import java.util.Locale

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
        val audioDrivers = stringArrayResource(R.array.audio_driver_entries).toList()
        val gpuCards = ContainerUtils.getGPUCards(context)
        val renderingModes = stringArrayResource(R.array.offscreen_rendering_modes).toList()
        val videoMemSizes = stringArrayResource(R.array.video_memory_size_entries).toList()
        val mouseWarps = stringArrayResource(R.array.mouse_warp_override_entries).toList()
        val winCompOpts = stringArrayResource(R.array.win_component_entries).toList()
        val box64Versions = stringArrayResource(R.array.box64_version_entries).toList()
        val box64Presets = Box86_64PresetManager.getPresets("box64", context)
        val startupSelectionEntries = stringArrayResource(R.array.startup_selection_entries).toList()
        val turnipVersions = stringArrayResource(R.array.turnip_version_entries).toList()
        val virglVersions = stringArrayResource(R.array.virgl_version_entries).toList()
        val zinkVersions = stringArrayResource(R.array.zink_version_entries).toList()
        val vortekVersions = stringArrayResource(R.array.vortek_version_entries).toList()
        val adrenoVersions = stringArrayResource(R.array.adreno_version_entries).toList()
        val sd8EliteVersions = stringArrayResource(R.array.sd8elite_version_entries).toList()
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
            val constrained   = if (isVortekLike)
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
                kvs.put("vkd3dVersion", vkd3dForcedVersion())
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
                    Column(
                        modifier = Modifier
                            .verticalScroll(scrollState)
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
                        SettingsGroup(
                            title = { Text(text = "General") },
                        ) {
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
                            // TODO: add way to pick driver version
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
                                    // Get the version directly from the selected item
                                    val selectedVersion = if (it == 0) {
                                        "" // Default
                                    } else {
                                        getVersionsForDriver()[it]
                                    }
                                    config = config.copy(graphicsDriverVersion = selectedVersion)
                                },
                            )
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
                                val items = if (isVortekLike) listOf("1.10.3", "1.10.9-sarek", "1.9.2", "async-1.10.3") else dxvkVersionsAll
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
                                            if (version.contains("async", ignoreCase = true)) envVarsSet.put("DXVK_ASYNC", "1") else envVarsSet.remove("DXVK_ASYNC")
                                            config = config.copy(dxwrapperConfig = currentConfig.toString(), envVars = envVarsSet.toString())
                                        },
                                    )
                                } else {
                                    // Ensure default version for vortek-like when hidden
                                    val version = if (isVortekLike) "1.10.3" else "2.3.1"
                                    val currentConfig = KeyValueSet(config.dxwrapperConfig)
                                    currentConfig.put("version", version)
                                    config = config.copy(dxwrapperConfig = currentConfig.toString())
                                }
                            }
                            // VKD3D Version UI (visible only when VKD3D selected)
                            run {
                                val isVKD3D = StringUtils.parseIdentifier(dxWrappers[dxWrapperIndex]) == "vkd3d"
                                if (isVKD3D) {
                                    val driverType = StringUtils.parseIdentifier(graphicsDrivers[graphicsDriverIndex])
                                    val isVortekLike = driverType == "vortek" || driverType == "adreno" || driverType == "sd-8-elite"
                                    val label = "VKD3D Version"
                                    val version = vkd3dForcedVersion()

                                    // Save VKD3D version in config - exact same pattern as DXVK
                                    val currentConfig = KeyValueSet(config.dxwrapperConfig)
                                    currentConfig.put("vkd3dVersion", version)
                                    config = config.copy(dxwrapperConfig = currentConfig.toString())

                                    SettingsListDropdown(
                                        colors = settingsTileColors(),
                                        title = { Text(text = label) },
                                        value = 0,
                                        items = listOf(version),
                                        onItemSelected = { _ -> },
                                    )
                                }
                            }
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
                            // Steam Type Dropdown
                            val steamTypeItems = listOf("Normal", "Light", "Ultra Light")
                            val currentSteamTypeIndex = when (config.steamType.lowercase()) {
                                com.winlator.container.Container.STEAM_TYPE_LIGHT -> 1
                                com.winlator.container.Container.STEAM_TYPE_ULTRALIGHT -> 2
                                else -> 0
                            }
                            SettingsListDropdown(
                                colors = settingsTileColors(),
                                title = { Text(text = "Steam Type") },
                                value = currentSteamTypeIndex,
                                items = steamTypeItems,
                                onItemSelected = {
                                    val type = when (it) {
                                        1 -> com.winlator.container.Container.STEAM_TYPE_LIGHT
                                        2 -> com.winlator.container.Container.STEAM_TYPE_ULTRALIGHT
                                        else -> com.winlator.container.Container.STEAM_TYPE_NORMAL
                                    }
                                    config = config.copy(steamType = type)
                                },
                            )
                        }
                        SettingsGroup(title = { Text(text = "Controller") }) {
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
                                    "A","B","X","Y","L1","L2","L3","R1","R2","R3",
                                    "DPAD_UP","DPAD_DOWN","DPAD_LEFT","DPAD_RIGHT","START","SELECT"
                                )
                                val context = LocalContext.current
                                val currentMap = try {
                                    org.json.JSONObject(config.controllerEmulationBindings)
                                } catch (_: Exception) { org.json.JSONObject() }
                                val bindingLabels = com.winlator.inputcontrols.Binding.keyboardBindingLabels().toList() +
                                        com.winlator.inputcontrols.Binding.mouseBindingLabels().toList()
                                val bindingValues = com.winlator.inputcontrols.Binding.keyboardBindingValues().map { it.name }.toList() +
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
                                            } catch (_: Exception) {}
                                        }
                                    )
                                }
                            }
                        }
                        SettingsGroup(title = { Text(text = "Wine Configuration") }) {
                            // TODO: add desktop settings
                            SettingsListDropdown(
                                colors = settingsTileColors(),
                                title = { Text(text = "GPU Name") },
                                subtitle = { Text(text = "WineD3D") },
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
                                subtitle = { Text(text = "WineD3D") },
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
                                subtitle = { Text(text = "WineD3D") },
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
                                subtitle = { Text(text = "WineD3D") },
                                state = config.csmt,
                                onCheckedChange = {
                                    config = config.copy(csmt = it)
                                },
                            )
                            SettingsSwitch(
                                colors = settingsTileColorsAlt(),
                                title = { Text(text = "Enable Strict Shader Math") },
                                subtitle = { Text(text = "WineD3D") },
                                state = config.strictShaderMath,
                                onCheckedChange = {
                                    config = config.copy(strictShaderMath = it)
                                },
                            )
                            SettingsListDropdown(
                                colors = settingsTileColors(),
                                title = { Text(text = "Mouse Warp Override") },
                                subtitle = { Text(text = "DirectInput") },
                                value = mouseWarpIndex,
                                items = mouseWarps,
                                onItemSelected = {
                                    mouseWarpIndex = it
                                    config = config.copy(mouseWarpOverride = mouseWarps[it].lowercase())
                                },
                            )
                        }
                        SettingsGroup(title = { Text(text = "Win Components") }) {
                            for (wincomponent in KeyValueSet(config.wincomponents)) {
                                val compId = wincomponent[0]
                                val compName = winComponentsItemTitle(compId)
                                val compValue = wincomponent[1].toInt()
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(compName) },
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
                        SettingsGroup(title = { Text(text = "Environment Variables") }) {
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
                        SettingsGroup(title = { Text(text = "Drives") }) {
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
                        SettingsGroup(title = { Text(text = "Advanced") }) {
                            SettingsListDropdown(
                                colors = settingsTileColors(),
                                title = { Text(text = "Box64 Version") },
                                subtitle = { Text(text = "Box64") },
                                value = box64Versions.indexOfFirst { StringUtils.parseIdentifier(it) == config.box64Version },
                                items = box64Versions,
                                onItemSelected = {
                                    config = config.copy(
                                        box64Version = StringUtils.parseIdentifier(box64Versions[it]),
                                    )
                                },
                            )
                            SettingsListDropdown(
                                colors = settingsTileColors(),
                                title = { Text(text = "Box64 Preset") },
                                subtitle = { Text(text = "Box64") },
                                value = box64Presets.indexOfFirst { it.id == config.box64Preset },
                                items = box64Presets.map { it.name },
                                onItemSelected = {
                                    config = config.copy(
                                        box64Preset = box64Presets[it].id,
                                    )
                                },
                            )
                            SettingsListDropdown(
                                colors = settingsTileColors(),
                                title = { Text(text = "Startup Selection") },
                                subtitle = { Text(text = "System") },
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
            },
        )
    }
}

/**
 * Gets the component title for Win Components settings group.
 */
@Composable
private fun winComponentsItemTitle(string: String): String {
    val resource = when (string) {
        "direct3d" -> R.string.direct3d
        "directsound" -> R.string.directsound
        "directmusic" -> R.string.directmusic
        "directplay" -> R.string.directplay
        "directshow" -> R.string.directshow
        "directx" -> R.string.directx
        "vcrun2010" -> R.string.vcrun2010
        "wmdecoder" -> R.string.wmdecoder
        else -> throw IllegalArgumentException("No string res found for Win Components title: $string")
    }
    return stringResource(resource)
}
