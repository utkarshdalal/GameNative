package app.gamenative.ui.screen.settings

import android.content.res.Configuration
import android.os.Environment
import android.os.storage.StorageManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.PrefManager
import app.gamenative.enums.AppTheme
import app.gamenative.ui.component.dialog.SingleChoiceDialog
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsSwitch
import com.materialkolor.PaletteStyle
import kotlinx.serialization.json.Json
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import app.gamenative.ui.component.settings.SettingsListDropdown
import app.gamenative.ui.theme.PluviaTheme
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.ImageView
import app.gamenative.utils.IconSwitcher
import com.alorma.compose.settings.ui.SettingsMenuLink
import androidx.compose.material3.Slider
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import kotlin.math.roundToInt
import com.winlator.core.AppUtils
import app.gamenative.ui.component.dialog.MessageDialog
import app.gamenative.ui.component.dialog.LoadingDialog
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.gamenative.utils.LocaleHelper

@Composable
fun SettingsGroupInterface(
    appTheme: AppTheme,
    paletteStyle: PaletteStyle,
    onAppTheme: (AppTheme) -> Unit,
    onPaletteStyle: (PaletteStyle) -> Unit,
) {
    val context = LocalContext.current

    var openWebLinks by rememberSaveable { mutableStateOf(PrefManager.openWebLinksExternally) }

    var openAppThemeDialog by rememberSaveable { mutableStateOf(false) }
    var openAppPaletteDialog by rememberSaveable { mutableStateOf(false) }

    var openStartScreenDialog by rememberSaveable { mutableStateOf(false) }
    var startScreenOption by rememberSaveable(openStartScreenDialog) { mutableStateOf(PrefManager.startScreen) }

    // Status bar hide/show confirmation dialog
    var showStatusBarRestartDialog by rememberSaveable { mutableStateOf(false) }
    var pendingStatusBarValue by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var showStatusBarLoadingDialog by rememberSaveable { mutableStateOf(false) }
    var hideStatusBar by rememberSaveable { mutableStateOf(PrefManager.hideStatusBarWhenNotInGame) }

    // Language selection dialog
    var openLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageRestartDialog by rememberSaveable { mutableStateOf(false) }
    var pendingLanguageCode by rememberSaveable { mutableStateOf<String?>(null) }
    var showLanguageLoadingDialog by rememberSaveable { mutableStateOf(false) }
    val languageCodes = remember { LocaleHelper.getSupportedLanguageCodes() }
    val languageNames = remember { LocaleHelper.getSupportedLanguageNames() }
    var selectedLanguageIndex by rememberSaveable {
        mutableStateOf(
            languageCodes.indexOf(PrefManager.appLanguage).takeIf { it >= 0 } ?: 0
        )
    }

    // Load Steam regions from assets
    val steamRegionsMap: Map<Int, String> = remember {
        val jsonString = context.assets.open("steam_regions.json").bufferedReader().use { it.readText() }
        Json.decodeFromString<Map<String, String>>(jsonString).mapKeys { it.key.toInt() }
    }
    val steamRegionsList = remember {
        // Always put 'Automatic' (id 0) first, then sort the rest alphabetically
        val entries = steamRegionsMap.toList()
        val (autoEntries, otherEntries) = entries.partition { it.first == 0 }
        autoEntries + otherEntries.sortedBy { it.second }
    }
    var openRegionDialog by rememberSaveable { mutableStateOf(false) }
    var selectedRegionIndex by rememberSaveable { mutableStateOf(
        steamRegionsList.indexOfFirst { it.first == PrefManager.cellId }.takeIf { it >= 0 } ?: 0
    ) }

    SettingsGroup(title = { Text(text = stringResource(R.string.settings_interface_title)) }) {
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.settings_interface_external_links_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_interface_external_links_subtitle)) },
            state = openWebLinks,
            onCheckedChange = {
                openWebLinks = it
                PrefManager.openWebLinksExternally = it
            },
        )

        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.settings_interface_hide_statusbar_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_interface_hide_statusbar_subtitle)) },
            state = hideStatusBar,
            onCheckedChange = { newValue ->
                // Update UI immediately for responsive feel
                hideStatusBar = newValue
                // Store the pending value and show confirmation dialog
                pendingStatusBarValue = newValue
                showStatusBarRestartDialog = true
            },
        )

        // Language selection
        SettingsMenuLink(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.settings_language)) },
            subtitle = { Text(text = LocaleHelper.getLanguageDisplayName(PrefManager.appLanguage)) },
            onClick = { openLanguageDialog = true }
        )

        // Unified visual icon picker (affects app and notification icons)
        var selectedVariant by rememberSaveable { mutableStateOf(if (PrefManager.useAltLauncherIcon || PrefManager.useAltNotificationIcon) 1 else 0) }
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(text = stringResource(R.string.settings_interface_icon_style))
            Spacer(modifier = Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconVariantCard(
                    label = stringResource(R.string.settings_theme_default),
                    launcherIconRes = R.mipmap.ic_launcher,
                    notificationIconRes = R.drawable.ic_notification,
                    selected = selectedVariant == 0,
                    onClick = {
                        selectedVariant = 0
                        PrefManager.useAltLauncherIcon = false
                        PrefManager.useAltNotificationIcon = false
                        IconSwitcher.applyLauncherIcon(context, false)
                    },
                )
                IconVariantCard(
                    label = stringResource(R.string.settings_theme_alternate),
                    launcherIconRes = R.mipmap.ic_launcher_alt,
                    notificationIconRes = R.drawable.ic_notification_alt,
                    selected = selectedVariant == 1,
                    onClick = {
                        selectedVariant = 1
                        PrefManager.useAltLauncherIcon = true
                        PrefManager.useAltNotificationIcon = true
                        IconSwitcher.applyLauncherIcon(context, true)
                    },
                )
            }
        }
    }

    // Downloads settings
    SettingsGroup(title = { Text(text = stringResource(R.string.settings_downloads_title)) }) {
        var wifiOnlyDownload by rememberSaveable { mutableStateOf(PrefManager.downloadOnWifiOnly) }
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.settings_interface_wifi_only_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_interface_wifi_only_subtitle)) },
            state = wifiOnlyDownload,
            onCheckedChange = {
                wifiOnlyDownload = it
                PrefManager.downloadOnWifiOnly = it
            },
        )

        // Download speed setting
        val downloadSpeedLabels = listOf(
            stringResource(R.string.settings_download_slow),
            stringResource(R.string.settings_download_medium),
            stringResource(R.string.settings_download_fast),
            stringResource(R.string.settings_download_blazing)
        )
        val downloadSpeedValues = remember { listOf(8, 16, 24, 32) }
        var downloadSpeedValue by rememberSaveable {
            mutableStateOf(
                downloadSpeedValues.indexOf(PrefManager.downloadSpeed).takeIf { it >= 0 }?.toFloat() ?: 2f
            )
        }
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_download_speed),
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = stringResource(R.string.settings_download_heat_warning),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.size(8.dp))
            Slider(
                value = downloadSpeedValue,
                onValueChange = { newIndex ->
                    downloadSpeedValue = newIndex
                    val index = newIndex.roundToInt().coerceIn(0, 3)
                    PrefManager.downloadSpeed = downloadSpeedValues[index]
                },
                valueRange = 0f..3f,
                steps = 2, // Creates exactly 4 positions: 0, 1, 2, 3
            )
            // Labels below slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                downloadSpeedLabels.forEach { label ->
                    Text(
                        text = label,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(60.dp)
                    )
                }
            }
        }

        val ctx = LocalContext.current
        val sm = ctx.getSystemService(StorageManager::class.java)

        // All writable volumes: primary first, then every SD / USB
        val dirs = remember {
            ctx.getExternalFilesDirs(null)
                .filterNotNull()
                .filter { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
                .filter { sm.getStorageVolume(it)?.isPrimary != true }
        }

        // Labels the user sees
        val labels = remember(dirs) {
            dirs.map { dir ->
                sm.getStorageVolume(dir)?.getDescription(ctx) ?: dir.name
            }
        }
        var useExternalStorage by rememberSaveable { mutableStateOf(PrefManager.useExternalStorage) }
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            enabled  = dirs.isNotEmpty(),
            title = { Text(text = stringResource(R.string.settings_interface_external_storage_title)) },
            subtitle = {
                if (dirs.isEmpty())
                    Text(stringResource(R.string.settings_interface_no_external_storage))
                else
                    Text(stringResource(R.string.settings_interface_external_storage_subtitle))
            },
            state = useExternalStorage,
            onCheckedChange = {
                useExternalStorage = it
                PrefManager.useExternalStorage = it
                if (it && dirs.isNotEmpty()) {
                    PrefManager.externalStoragePath = dirs[0].absolutePath
                }
            },
        )
        if (useExternalStorage) {
            // Currently selected item
            var selectedIndex by rememberSaveable {
                mutableStateOf(
                    dirs.indexOfFirst { it.absolutePath == PrefManager.externalStoragePath }
                        .takeIf { it >= 0 } ?: 0
                )
            }
            SettingsListDropdown(
                title = { Text(text = stringResource(R.string.settings_interface_storage_volume_title)) },
                items = labels,
                value = selectedIndex,
                onItemSelected = { idx ->
                    selectedIndex = idx
                    PrefManager.externalStoragePath = dirs[idx].absolutePath
                },
                colors = settingsTileColorsAlt()
            )
        }
        // Steam download server selection
        SettingsMenuLink(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.settings_interface_download_server_title)) },
            subtitle = { Text(text = steamRegionsList.getOrNull(selectedRegionIndex)?.second ?: stringResource(R.string.settings_region_default)) },
            onClick = { openRegionDialog = true }
        )
    }

    // Steam Download Server choice dialog
    SingleChoiceDialog(
        openDialog = openRegionDialog,
        icon = Icons.Default.Map,
        iconDescription = stringResource(R.string.settings_interface_download_server_title),
        title = stringResource(R.string.settings_interface_download_server_title),
        items = steamRegionsList.map { it.second },
        currentItem = selectedRegionIndex,
        onSelected = { index ->
            selectedRegionIndex = index
            val selectedId = steamRegionsList[index].first
            PrefManager.cellId = selectedId
            PrefManager.cellIdManuallySet = selectedId != 0
        },
        onDismiss = { openRegionDialog = false }
    )

    // Status bar restart confirmation dialog
    MessageDialog(
        visible = showStatusBarRestartDialog,
        title = stringResource(R.string.settings_interface_restart_required_title),
        message = stringResource(R.string.settings_language_restart_message),
        confirmBtnText = stringResource(R.string.settings_language_restart_confirm),
        dismissBtnText = stringResource(R.string.cancel),
        onConfirmClick = {
            showStatusBarRestartDialog = false
            val newValue = pendingStatusBarValue ?: return@MessageDialog
            // Save preference and show loading dialog
            PrefManager.hideStatusBarWhenNotInGame = newValue
            showStatusBarLoadingDialog = true
            pendingStatusBarValue = null
        },
        onDismissRequest = {
            showStatusBarRestartDialog = false
            // Revert toggle to original value
            hideStatusBar = PrefManager.hideStatusBarWhenNotInGame
            pendingStatusBarValue = null
        },
        onDismissClick = {
            showStatusBarRestartDialog = false
            // Revert toggle to original value
            hideStatusBar = PrefManager.hideStatusBarWhenNotInGame
            pendingStatusBarValue = null
        }
    )

    // Loading dialog while saving and restarting
    LaunchedEffect(showStatusBarLoadingDialog) {
        if (showStatusBarLoadingDialog) {
            // Wait a bit for the preference to be saved (DataStore operations are async)
            delay(300)
            // Verify the preference was saved by reading it back
            withContext(Dispatchers.IO) {
                // Small delay to ensure DataStore write completes
                delay(200)
            }
            // Restart the app
            AppUtils.restartApplication(context)
        }
    }

    LoadingDialog(
        visible = showStatusBarLoadingDialog,
        progress = -1f, // Indeterminate progress
        message = context.getString(R.string.settings_saving_restarting)
    )

    // Language selection dialog
    SingleChoiceDialog(
        openDialog = openLanguageDialog,
        icon = Icons.Default.Map,
        iconDescription = stringResource(R.string.settings_language),
        title = stringResource(R.string.settings_select_language),
        items = languageNames,
        currentItem = selectedLanguageIndex,
        onSelected = { index ->
            selectedLanguageIndex = index
            val selectedCode = languageCodes[index]
            // Check if language actually changed
            if (selectedCode != PrefManager.appLanguage) {
                pendingLanguageCode = selectedCode
                showLanguageRestartDialog = true
            }
            openLanguageDialog = false
        },
        onDismiss = { openLanguageDialog = false }
    )

    // Language change restart confirmation dialog
    MessageDialog(
        visible = showLanguageRestartDialog,
        title = stringResource(R.string.settings_language_restart_title),
        message = stringResource(R.string.settings_language_restart_message),
        confirmBtnText = stringResource(R.string.settings_language_restart_confirm),
        dismissBtnText = stringResource(R.string.cancel),
        onConfirmClick = {
            showLanguageRestartDialog = false
            val newLanguage = pendingLanguageCode ?: return@MessageDialog
            // Save preference and show loading dialog
            PrefManager.appLanguage = newLanguage
            showLanguageLoadingDialog = true
            pendingLanguageCode = null
        },
        onDismissRequest = {
            showLanguageRestartDialog = false
            // Revert selection to original value
            selectedLanguageIndex = languageCodes.indexOf(PrefManager.appLanguage).takeIf { it >= 0 } ?: 0
            pendingLanguageCode = null
        },
        onDismissClick = {
            showLanguageRestartDialog = false
            // Revert selection to original value
            selectedLanguageIndex = languageCodes.indexOf(PrefManager.appLanguage).takeIf { it >= 0 } ?: 0
            pendingLanguageCode = null
        }
    )

    // Loading dialog while saving and restarting for language change
    LaunchedEffect(showLanguageLoadingDialog) {
        if (showLanguageLoadingDialog) {
            // Wait a bit for the preference to be saved (DataStore operations are async)
            delay(300)
            // Verify the preference was saved by reading it back
            withContext(Dispatchers.IO) {
                // Small delay to ensure DataStore write completes
                delay(200)
            }
            // Restart the app
            AppUtils.restartApplication(context)
        }
    }

    LoadingDialog(
        visible = showLanguageLoadingDialog,
        progress = -1f, // Indeterminate progress
        message = stringResource(R.string.settings_language_changing)
    )
}


@Composable
private fun IconVariantCard(
    label: String,
    launcherIconRes: Int,
    notificationIconRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val border = if (selected) BorderStroke(2.dp, Color(0xFF4F46E5)) else BorderStroke(1.dp, Color(0x33404040))
    Card(
        modifier = Modifier
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        border = border,
        colors = CardDefaults.cardColors(),
    ) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.BottomEnd) {
                AndroidView(
                    modifier = Modifier.matchParentSize(),
                    factory = { ctx ->
                        ImageView(ctx).apply {
                            setImageResource(launcherIconRes)
                            scaleType = ImageView.ScaleType.CENTER_CROP
                        }
                    },
                )
                Image(
                    painter = painterResource(id = notificationIconRes),
                    contentDescription = "$label notification icon",
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(modifier = Modifier.size(8.dp))
            Text(text = label)
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_SettingsScreen() {
    val context = LocalContext.current
    PrefManager.init(context)
    PluviaTheme {
        SettingsGroupInterface (
            appTheme = AppTheme.DAY,
            paletteStyle = PaletteStyle.TonalSpot,
            onAppTheme = { },
            onPaletteStyle = { },
        )
    }
}
