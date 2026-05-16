package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.R
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.ui.component.settings.SettingsListDropdown
import app.gamenative.utils.SteamUtils
import com.alorma.compose.settings.ui.SettingsSwitch
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsGroup
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.core.DefaultVersion
import com.winlator.core.KeyValueSet
import com.winlator.core.StringUtils
import com.winlator.contents.ContentProfile
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

@Composable
fun GeneralTabContent(
    state: ContainerConfigState,
    nonzeroResolutionError: String,
    aspectResolutionError: String,
) {
    val config = state.config.value
    val graphicsDrivers = state.graphicsDrivers.value
    val glibcWineEntries = state.glibcWineEntries.value
    val bionicWineEntries = state.bionicWineEntries.value
    val suspendBehaviorEntries = listOf(
        stringResource(R.string.suspend_behavior_manual),
        stringResource(R.string.suspend_behavior_auto),
        stringResource(R.string.suspend_behavior_never),
    )
    val suspendBehaviorIndex = when {
        config.suspendPolicy.equals(Container.SUSPEND_POLICY_MANUAL, ignoreCase = true) -> 0
        config.suspendPolicy.equals(Container.SUSPEND_POLICY_NEVER, ignoreCase = true) -> 2
        else -> 1
    }

    if (state.showCustomResolutionDialog.value) {
        AlertDialog(
            onDismissRequest = { state.showCustomResolutionDialog.value = false },
            title = { Text(text = stringResource(R.string.container_config_custom_resolution_title)) },
            text = {
                val heightFocusRequester = remember { FocusRequester() }
                Column {
                    Row {
                        NoExtractOutlinedTextField(
                            modifier = Modifier.width(128.dp),
                            value = state.customScreenWidth.value,
                            onValueChange = { state.customScreenWidth.value = it },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { heightFocusRequester.requestFocus() }),
                            label = { Text(text = stringResource(R.string.width)) },
                            singleLine = true,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            modifier = Modifier.align(Alignment.CenterVertically),
                            text = stringResource(R.string.container_config_custom_resolution_separator),
                            style = TextStyle(fontSize = 16.sp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        NoExtractOutlinedTextField(
                            modifier = Modifier.width(128.dp).focusRequester(heightFocusRequester),
                            value = state.customScreenHeight.value,
                            onValueChange = { state.customScreenHeight.value = it },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            label = { Text(text = stringResource(R.string.height)) },
                            singleLine = true,
                        )
                    }
                    if (state.customResolutionValidationError.value != null) {
                        Text(
                            text = state.customResolutionValidationError.value!!,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                            style = TextStyle(fontSize = 16.sp),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val widthInt = state.customScreenWidth.value.toIntOrNull() ?: 0
                        val heightInt = state.customScreenHeight.value.toIntOrNull() ?: 0
                        if (widthInt == 0 || heightInt == 0) {
                            state.customResolutionValidationError.value = nonzeroResolutionError
                        } else if (widthInt <= heightInt) {
                            state.customResolutionValidationError.value = aspectResolutionError
                        } else {
                            state.customResolutionValidationError.value = null
                            state.applyScreenSizeToConfig()
                            state.showCustomResolutionDialog.value = false
                        }
                    },
                ) {
                    Text(text = stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { state.showCustomResolutionDialog.value = false },
                ) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }

    SettingsGroup() {
        run {
            val variantIndex = rememberSaveable {
                mutableIntStateOf(
                    state.containerVariants.indexOfFirst { it.equals(config.containerVariant, true) }.coerceAtLeast(0)
                )
            }
            SettingsListDropdown(
                colors = settingsTileColors(),
                title = { Text(text = stringResource(R.string.container_variant)) },
                value = variantIndex.value,
                items = state.containerVariants,
                onItemSelected = { idx ->
                    variantIndex.value = idx
                    val newVariant = state.containerVariants[idx]
                    if (newVariant.equals(Container.GLIBC, ignoreCase = true)) {
                        val defaultDriver = Container.DEFAULT_GRAPHICS_DRIVER
                        val newCfg = KeyValueSet(config.graphicsDriverConfig).apply {
                            put("version", "")
                            put("syncFrame", "0")
                            put("disablePresentWait", get("disablePresentWait").ifEmpty { "0" })
                            if (get("presentMode").isEmpty()) put("presentMode", "mailbox")
                            if (get("resourceType").isEmpty()) put("resourceType", "auto")
                            if (get("bcnEmulation").isEmpty()) put("bcnEmulation", "auto")
                            if (get("bcnEmulationType").isEmpty()) put("bcnEmulationType", "compute")
                            if (get("bcnEmulationCache").isEmpty()) put("bcnEmulationCache", "0")
                            put("adrenotoolsTurnip", "1")
                        }
                        state.graphicsDriverIndex.value =
                            graphicsDrivers.indexOfFirst { StringUtils.parseIdentifier(it) == defaultDriver }.coerceAtLeast(0)
                        state.graphicsDriverVersionIndex.value = 0
                        state.syncEveryFrameChecked.value = false
                        state.disablePresentWaitChecked.value = newCfg.get("disablePresentWait", "0") == "1"
                        state.bcnEmulationCacheEnabled.value = newCfg.get("bcnEmulationCache", "0") == "1"
                        state.adrenotoolsTurnipChecked.value = true

                        val defaultGlibcWine = glibcWineEntries.firstOrNull() ?: Container.DEFAULT_WINE_VERSION
                        state.config.value = config.copy(
                            containerVariant = newVariant,
                            wineVersion = defaultGlibcWine,
                            graphicsDriver = defaultDriver,
                            graphicsDriverVersion = "",
                            graphicsDriverConfig = newCfg.toString(),
                            box64Version = "0.3.6",
                        )
                    } else {
                        val defaultBionicDriver = StringUtils.parseIdentifier(state.bionicGraphicsDrivers.first())
                        val newWine = if (config.wineVersion == (glibcWineEntries.firstOrNull() ?: Container.DEFAULT_WINE_VERSION))
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
                            if (get("bcnEmulationType").isEmpty()) put("bcnEmulationType", "compute")
                            if (get("bcnEmulationCache").isEmpty()) put("bcnEmulationCache", "0")
                        }
                        state.bionicDriverIndex.value = 0
                        state.wrapperVersionIndex.value = state.wrapperOptions.ids
                            .indexOfFirst { it.equals(DefaultVersion.WRAPPER, true) }
                            .let { if (it >= 0) it else 0 }
                        state.syncEveryFrameChecked.value = false
                        state.disablePresentWaitChecked.value = newCfg.get("disablePresentWait", "0") == "1"
                        state.bcnEmulationCacheEnabled.value = newCfg.get("bcnEmulationCache", "0") == "1"
                        state.adrenotoolsTurnipChecked.value = true
                        state.maxDeviceMemoryIndex.value =
                            listOf("0", "512", "1024", "2048", "4096").indexOf("4096").coerceAtLeast(0)

                        val currentConfig = KeyValueSet(config.dxwrapperConfig)
                        currentConfig.put("version", "async-1.10.3")
                        currentConfig.put("async", "1")
                        currentConfig.put("asyncCache", "0")
                        state.config.value = config.copy(dxwrapperConfig = currentConfig.toString())

                        state.config.value = config.copy(
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
            if (config.containerVariant.equals(Container.BIONIC, ignoreCase = true)) {
                val wineIndex = state.bionicWineOptions.ids.indexOfFirst { it == config.wineVersion }.coerceAtLeast(0)
                SettingsListDropdown(
                    colors = settingsTileColors(),
                    title = { Text(text = stringResource(R.string.wine_version)) },
                    value = wineIndex,
                    items = state.bionicWineOptions.labels,
                    itemMuted = state.bionicWineOptions.muted,
                    onItemSelected = { idx ->
                        val selectedId = state.bionicWineOptions.ids.getOrNull(idx).orEmpty()
                        val isManifestNotInstalled = state.bionicWineOptions.muted.getOrNull(idx) == true
                        val manifestEntry = state.bionicWineManifestById[selectedId]
                        if (isManifestNotInstalled && manifestEntry != null) {
                            val expectedType = if (selectedId.startsWith("proton", true)) {
                                ContentProfile.ContentType.CONTENT_TYPE_PROTON
                            } else {
                                ContentProfile.ContentType.CONTENT_TYPE_WINE
                            }
                            state.launchManifestContentInstall(manifestEntry, expectedType) {
                                state.config.value = config.copy(wineVersion = selectedId)
                            }
                            return@SettingsListDropdown
                        }
                        state.config.value = config.copy(wineVersion = selectedId.ifEmpty { state.bionicWineOptions.labels[idx] })
                    },
                )
            }
        }
        ExecutablePathDropdown(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            value = config.executablePath,
            onValueChange = { state.config.value = config.copy(executablePath = it) },
            containerData = config,
        )
        NoExtractOutlinedTextField(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            value = config.execArgs,
            onValueChange = { state.config.value = config.copy(execArgs = it) },
            label = { Text(text = stringResource(R.string.exec_arguments)) },
            placeholder = { Text(text = stringResource(R.string.exec_arguments_example)) },
            singleLine = true,
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
            value = state.languageIndex.value,
            items = state.languages.map(displayNameForLanguage),
            fallbackDisplay = displayNameForLanguage("english"),
            onItemSelected = { index ->
                state.languageIndex.value = index
                state.config.value = config.copy(language = state.languages[index])
            },
            title = { Text(text = stringResource(R.string.language)) },
            colors = settingsTileColors(),
        )
        SettingsListDropdown(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.screen_size)) },
            value = state.screenSizeIndex.value,
            items = state.screenSizes,
            onItemSelected = {
                state.screenSizeIndex.value = it
                if (it == 0) {
                    state.showCustomResolutionDialog.value = true
                } else {
                    state.applyScreenSizeToConfig()
                }
            },
        )
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.portrait_mode)) },
            subtitle = { Text(text = stringResource(R.string.portrait_mode_description)) },
            state = config.portraitMode,
            onCheckedChange = { state.config.value = config.copy(portraitMode = it) },
        )
        SettingsListDropdown(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.audio_driver)) },
            value = state.audioDriverIndex.value,
            items = state.audioDrivers,
            onItemSelected = {
                state.audioDriverIndex.value = it
                state.config.value = config.copy(audioDriver = StringUtils.parseIdentifier(state.audioDrivers[it]))
            },
        )
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.force_dlc)) },
            subtitle = { Text(text = stringResource(R.string.force_dlc_description)) },
            state = config.forceDlc,
            onCheckedChange = { state.config.value = config.copy(forceDlc = it) },
        )
//        SettingsSwitch(
//            colors = settingsTileColorsAlt(),
//            title = { Text(text = stringResource(R.string.local_saves_only)) },
//            subtitle = { Text(text = stringResource(R.string.local_saves_only_description)) },
//            state = config.localSavesOnly,
//            onCheckedChange = { state.config.value = config.copy(localSavesOnly = it) },
//        )
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.use_legacy_drm)) },
            state = config.useLegacyDRM,
            onCheckedChange = { state.config.value = config.copy(useLegacyDRM = it) },
        )
        if (!config.useLegacyDRM) {
            SettingsSwitch(
                colors = settingsTileColorsAlt(),
                title = { Text(text = stringResource(R.string.unpack_files)) },
                subtitle = { Text(text = stringResource(R.string.unpack_files_description)) },
                state = config.unpackFiles,
                onCheckedChange = { state.config.value = config.copy(unpackFiles = it) },
            )
        }
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.steam_offline_mode)) },
            subtitle = { Text(text = stringResource(R.string.steam_offline_mode_description)) },
            state = config.steamOfflineMode,
            onCheckedChange = { state.config.value = config.copy(steamOfflineMode = it) },
        )
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.launch_steam_client_beta)) },
            subtitle = { Text(text = stringResource(R.string.launch_steam_client_description)) },
            state = config.launchRealSteam,
            onCheckedChange = {
                state.config.value = if (it) {
                    config.copy(launchRealSteam = true, launchBionicSteam = false)
                } else {
                    config.copy(launchRealSteam = false)
                }
            },
        )
        if (config.launchRealSteam) {
            SettingsSwitch(
                colors = settingsTileColorsAlt(),
                title = { Text(text = stringResource(R.string.disable_steam_overlay)) },
                subtitle = { Text(text = stringResource(R.string.disable_steam_overlay_description)) },
                state = config.disableSteamOverlay,
                onCheckedChange = { state.config.value = config.copy(disableSteamOverlay = it) },
            )
        }
        if (config.containerVariant.equals(Container.BIONIC, ignoreCase = true)) {
            SettingsSwitch(
                colors = settingsTileColorsAlt(),
                title = { Text(text = stringResource(R.string.launch_bionic_steam)) },
                subtitle = { Text(text = stringResource(R.string.launch_bionic_steam_description)) },
                state = config.launchBionicSteam,
                onCheckedChange = {
                    state.config.value = if (it) {
                        config.copy(launchBionicSteam = true, launchRealSteam = false)
                    } else {
                        config.copy(launchBionicSteam = false)
                    }
                },
            )
        }
        if (config.launchRealSteam || config.launchBionicSteam) {
            SdkCloudSaveSubdirField(state = state, config = config)
        }
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
                state.config.value = config.copy(steamType = type)
            },
        )
        SettingsListDropdown(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.suspend_behavior)) },
            value = suspendBehaviorIndex,
            items = suspendBehaviorEntries,
            onItemSelected = { index ->
                val policy = when (index) {
                    0 -> Container.SUSPEND_POLICY_MANUAL
                    1 -> Container.SUSPEND_POLICY_AUTO
                    2 -> Container.SUSPEND_POLICY_NEVER
                    else -> Container.SUSPEND_POLICY_MANUAL
                }
                state.config.value = config.copy(suspendPolicy = policy)
            },
        )
    }
}

/**
 * Install-relative save subdir for Pattern B SDK-cloud games (game reads saves from
 * its install dir while cloud state lives in SteamUserData/<appid>/remote/). Empty
 * disables the mirror. "Use Recommended" pulls from the Ludusavi manifest.
 */
@Composable
private fun SdkCloudSaveSubdirField(
    state: ContainerConfigState,
    config: ContainerData,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var pendingValue by rememberSaveable { mutableStateOf("") }
    var detectMessage by rememberSaveable { mutableStateOf<String?>(null) }
    // `remember` not `rememberSaveable`: if we're disposed mid-fetch the coroutine
    // cancels, and a persisted `true` would leave the button permanently disabled on
    // recomposition. Transient state should reset on restart.
    var recommendLoading by remember { mutableStateOf(false) }
    val current = config.sdkCloudSaveSubdir
    val currentIsValid = current.isEmpty() || SteamUtils.isValidSdkCloudSubdir(current)
    val errorText = if (!currentIsValid) {
        stringResource(R.string.sdk_cloud_save_subdir_invalid)
    } else null

    Text(
        text = stringResource(R.string.sdk_cloud_save_subdir_section),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 2.dp),
    )
    Text(
        text = stringResource(R.string.sdk_cloud_save_subdir_section_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )

    NoExtractOutlinedTextField(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        value = current,
        // Manual typing is an intentional edit — commit each keystroke directly. The first-activation
        // confirmation flow (pendingValue/showConfirmDialog) is reserved for auto-filled values from
        // the Recommend / Detect buttons below, which the user didn't type and may want to reject.
        // Earlier shapes of this handler tried to gate typing through the confirmation too, but any
        // such gate either broke after the first keystroke (state was no longer "blank") or
        // interrupted normal multi-character typing.
        onValueChange = { raw ->
            state.config.value = config.copy(sdkCloudSaveSubdir = raw.trim())
        },
        label = { Text(text = stringResource(R.string.sdk_cloud_save_subdir_label)) },
        placeholder = { Text(text = stringResource(R.string.sdk_cloud_save_subdir_placeholder)) },
        supportingText = {
            Text(
                text = errorText
                    ?: detectMessage
                    ?: stringResource(R.string.sdk_cloud_save_subdir_description),
            )
        },
        isError = !currentIsValid,
        singleLine = true,
    )

    val appId = state.appId
    if (appId != null) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                enabled = !recommendLoading,
                onClick = {
                    recommendLoading = true
                    detectMessage = context.getString(R.string.sdk_cloud_save_subdir_recommended_loading)
                    scope.launch {
                        val rec = runCatching {
                            SteamUtils.getRecommendedSdkCloudSaveSubdirAsync(context, appId)
                        }.getOrNull()
                        recommendLoading = false
                        if (rec != null) {
                            detectMessage = context.getString(
                                R.string.sdk_cloud_save_subdir_recommended,
                                rec.name.ifEmpty { appId.toString() },
                                rec.subdir,
                            )
                            // Re-read after suspend: user may have typed in the field while
                            // we were off-thread asking Ludusavi for a recommendation.
                            if (state.config.value.sdkCloudSaveSubdir.isBlank()) {
                                pendingValue = rec.subdir
                                showConfirmDialog = true
                            }
                        } else {
                            detectMessage = context.getString(R.string.sdk_cloud_save_subdir_recommended_none)
                        }
                    }
                },
            ) {
                if (recommendLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(text = stringResource(R.string.sdk_cloud_save_subdir_recommended_button))
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = {
                scope.launch {
                    // File.listFiles / isDirectory on main thread can ANR on slow storage.
                    val detected = withContext(Dispatchers.IO) {
                        runCatching {
                            SteamUtils.detectSdkCloudSaveSubdir(context, appId)
                        }.getOrNull()
                    }
                    detectMessage = if (detected != null) {
                        context.getString(R.string.sdk_cloud_save_subdir_detected, detected)
                    } else {
                        context.getString(R.string.sdk_cloud_save_subdir_detect_none)
                    }
                    // Re-read after the IO suspend; the user may have typed in the field meanwhile.
                    if (detected != null && state.config.value.sdkCloudSaveSubdir.isBlank()) {
                        pendingValue = detected
                        showConfirmDialog = true
                    }
                }
            }) {
                Text(text = stringResource(R.string.sdk_cloud_save_subdir_detect))
            }
            if (current.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = {
                    state.config.value = config.copy(sdkCloudSaveSubdir = "")
                    detectMessage = null
                }) {
                    Text(text = stringResource(R.string.sdk_cloud_save_subdir_clear))
                }
            }
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text(text = stringResource(R.string.sdk_cloud_save_subdir_confirm_title)) },
            text = {
                Text(text = stringResource(R.string.sdk_cloud_save_subdir_confirm_body, pendingValue))
            },
            confirmButton = {
                TextButton(onClick = {
                    if (SteamUtils.isValidSdkCloudSubdir(pendingValue)) {
                        state.config.value = config.copy(sdkCloudSaveSubdir = pendingValue)
                    }
                    showConfirmDialog = false
                }) {
                    Text(text = stringResource(R.string.sdk_cloud_save_subdir_confirm_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text(text = stringResource(R.string.sdk_cloud_save_subdir_confirm_cancel))
                }
            },
        )
    }
}
