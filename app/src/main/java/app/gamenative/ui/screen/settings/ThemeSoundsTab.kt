package app.gamenative.ui.screen.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.service.SteamService
import app.gamenative.theme.StoreTheme
import app.gamenative.theme.sound.InstalledSoundPack
import app.gamenative.theme.sound.SoundPackManager
import app.gamenative.theme.sound.UiSound
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.ui.component.dialog.SettingsSliderBlock
import app.gamenative.ui.theme.MenuColorScope
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.DialogSound
import app.gamenative.ui.util.UiSoundCues
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREVIEW_GAP_MS = 300L
private const val VOLUME_SAVE_DELAY_MS = 150L
private const val FOCUS_SETTLE_MS = 150L
private const val FOCUS_ATTEMPTS = 4

private val PACK_ZIP_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/x-zip", "application/octet-stream")

private class SoundStoreState {
    var query by mutableStateOf("")
    val items = mutableStateListOf<StoreTheme>()
    var page by mutableStateOf(0)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var endReached by mutableStateOf(false)
    var loaded by mutableStateOf(false)
    val installing = mutableStateListOf<String>()
}

@Composable
internal fun ThemeSoundsTab(onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val packs by SoundPackManager.packs.collectAsState()
    val settings by SoundPackManager.settings.collectAsState()
    val store = remember { SoundStoreState() }
    var importing by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<InstalledSoundPack?>(null) }
    var volume by remember { mutableFloatStateOf(settings.volume) }
    var musicVolume by remember { mutableFloatStateOf(settings.musicVolume) }
    val listState = rememberLazyListState()
    val uiNoneFocus = remember { FocusRequester() }
    val musicNoneFocus = remember { FocusRequester() }
    var focusAfterDelete by remember { mutableStateOf<Boolean?>(null) }
    val deleteFocus = remember { HashMap<String, FocusRequester>() }
    var refocusDelete by remember { mutableStateOf<String?>(null) }
    val importFocus = remember { FocusRequester() }

    // Coming back from the file picker the window has no focused control; put it back on the import button.
    fun refocusImport() {
        scope.launch {
            repeat(FOCUS_ATTEMPTS) {
                delay(FOCUS_SETTLE_MS)
                runCatching { importFocus.requestFocus() }
            }
        }
    }

    val uiPacks = packs.filter { !it.isMusic }
    val musicPacks = packs.filter { it.isMusic }
    val installedNames = packs.map { it.name.lowercase() }.toSet()

    fun failureText(e: Throwable): String =
        ctx.getString(R.string.themes_failed_message, e.message ?: e.javaClass.simpleName)

    fun installedText(result: Result<InstalledSoundPack>): String =
        result.fold({ ctx.getString(R.string.themes_installed_message, it.name) }, { failureText(it) })

    fun runAction(block: suspend () -> Unit) {
        scope.launch { withContext(Dispatchers.IO) { block() } }
    }

    fun loadStore(reset: Boolean) {
        if (store.loading) return
        scope.launch {
            store.loading = true
            store.error = null
            val nextPage = if (reset) 1 else store.page + 1
            val result = withContext(Dispatchers.IO) { SoundPackManager.storeList(store.query.trim(), nextPage) }
            result.onSuccess { list ->
                if (reset) store.items.clear()
                store.items.addAll(list.filter { item -> store.items.none { it.id == item.id } })
                store.page = nextPage
                store.endReached = list.isEmpty()
                store.loaded = true
            }.onFailure {
                store.error = it.message ?: it.javaClass.simpleName
            }
            store.loading = false
        }
    }

    fun installFromStore(item: StoreTheme) {
        if (item.id in store.installing) return
        scope.launch {
            store.installing.add(item.id)
            val result = withContext(Dispatchers.IO) { SoundPackManager.installFromStore(item) }
            store.installing.remove(item.id)
            onMessage(installedText(result))
        }
    }

    fun preview(pack: InstalledSoundPack) {
        scope.launch {
            if (settings.activePackId != pack.id) withContext(Dispatchers.IO) { SoundPackManager.setActivePack(pack.id) }
            SoundPackManager.preview(UiSound.NAVIGATE)
            delay(PREVIEW_GAP_MS)
            SoundPackManager.preview(UiSound.SELECT)
        }
    }

    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        SteamService.isImporting = false
        refocusImport()
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            val result = withContext(Dispatchers.IO) { SoundPackManager.importZip(uri) }
            importing = false
            onMessage(installedText(result))
        }
    }

    LaunchedEffect(Unit) {
        if (!store.loaded) loadStore(reset = true)
    }

    // The deleted row held focus; move it to the None row of the same section instead of the top bar.
    LaunchedEffect(packs) {
        val music = focusAfterDelete ?: return@LaunchedEffect
        focusAfterDelete = null
        val uiCount = packs.count { !it.isMusic }
        val index = if (music) 3 + maxOf(uiCount, 1) + 3 + 1 else 3
        listState.scrollToItem(index)
        // The closing dialog hands focus back to the window a moment later; ask again until it lands.
        repeat(FOCUS_ATTEMPTS) {
            delay(FOCUS_SETTLE_MS)
            runCatching { (if (music) musicNoneFocus else uiNoneFocus).requestFocus() }
        }
    }

    LaunchedEffect(volume) {
        if (volume == settings.volume) return@LaunchedEffect
        delay(VOLUME_SAVE_DELAY_MS)
        withContext(Dispatchers.IO) { SoundPackManager.setVolume(volume) }
    }

    LaunchedEffect(musicVolume) {
        if (musicVolume == settings.musicVolume) return@LaunchedEffect
        delay(VOLUME_SAVE_DELAY_MS)
        withContext(Dispatchers.IO) { SoundPackManager.setMusicVolume(musicVolume) }
    }

    // A cancelled delete dialog gives focus back to the delete button that opened it.
    LaunchedEffect(refocusDelete) {
        val id = refocusDelete ?: return@LaunchedEffect
        repeat(FOCUS_ATTEMPTS) {
            delay(FOCUS_SETTLE_MS)
            runCatching { deleteFocus[id]?.requestFocus() }
        }
        refocusDelete = null
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "ui_switch") {
            SoundSwitchRow(
                title = stringResource(R.string.themes_sounds_interface),
                subtitle = stringResource(R.string.themes_sounds_interface_subtitle),
                checked = settings.enabled,
                onCheckedChange = { checked ->
                    runAction {
                        SoundPackManager.setEnabled(checked)
                        // The cue for the switch itself only exists once sounds are on, so confirm with the new pack.
                        if (checked) SoundPackManager.preview(UiSound.TOGGLE_ON)
                    }
                },
            )
        }
        item(key = "ui_volume") {
            SettingsSliderBlock(
                title = stringResource(R.string.themes_sounds_volume),
                subtitle = stringResource(R.string.themes_sounds_volume_subtitle),
                value = volume,
                valueRange = 0f..1f,
                valueText = percentText(volume),
                onValueChange = { volume = it },
                steps = 9,
                compact = true,
            )
        }
        item(key = "ui_header") { SoundSectionHeader(stringResource(R.string.themes_sounds_pack)) }
        item(key = "ui_none") {
            SoundPackRow(
                name = stringResource(R.string.themes_sounds_none),
                detail = null,
                selected = settings.activePackId == null,
                onSelect = { runAction { SoundPackManager.setActivePack(null) } },
                focusRequester = uiNoneFocus,
            )
        }
        items(uiPacks, key = { "ui:${it.id}" }) { pack ->
            SoundPackRow(
                name = pack.name,
                detail = packDetail(pack),
                selected = settings.activePackId == pack.id,
                onSelect = { runAction { SoundPackManager.setActivePack(pack.id) } },
                onPreview = { preview(pack) },
                onDelete = { deleteTarget = pack },
                deleteFocusRequester = deleteFocus.getOrPut(pack.id) { FocusRequester() },
            )
        }
        if (uiPacks.isEmpty()) {
            item(key = "ui_empty") { SoundHint(stringResource(R.string.themes_sounds_no_packs)) }
        }

        item(key = "music_switch") {
            SoundSwitchRow(
                title = stringResource(R.string.themes_sounds_music),
                subtitle = stringResource(R.string.themes_sounds_music_subtitle),
                checked = settings.musicEnabled,
                onCheckedChange = { checked -> runAction { SoundPackManager.setMusicEnabled(checked) } },
            )
        }
        item(key = "music_volume") {
            SettingsSliderBlock(
                title = stringResource(R.string.themes_sounds_music_volume),
                subtitle = stringResource(R.string.themes_sounds_music_volume_subtitle),
                value = musicVolume,
                valueRange = 0f..1f,
                valueText = percentText(musicVolume),
                onValueChange = { musicVolume = it },
                steps = 9,
                compact = true,
            )
        }
        item(key = "music_header") { SoundSectionHeader(stringResource(R.string.themes_sounds_music_pack)) }
        item(key = "music_none") {
            SoundPackRow(
                name = stringResource(R.string.themes_sounds_none),
                detail = null,
                selected = settings.activeMusicPackId == null,
                onSelect = { runAction { SoundPackManager.setActiveMusicPack(null) } },
                focusRequester = musicNoneFocus,
            )
        }
        items(musicPacks, key = { "music:${it.id}" }) { pack ->
            SoundPackRow(
                name = pack.name,
                detail = packDetail(pack),
                selected = settings.activeMusicPackId == pack.id,
                onSelect = { runAction { SoundPackManager.setActiveMusicPack(pack.id) } },
                onDelete = { deleteTarget = pack },
                deleteFocusRequester = deleteFocus.getOrPut(pack.id) { FocusRequester() },
            )
        }
        if (musicPacks.isEmpty()) {
            item(key = "music_empty") { SoundHint(stringResource(R.string.themes_sounds_no_music)) }
        }

        item(key = "import") {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                // One button in every state, so it keeps focus while the import runs.
                OutlinedButton(
                    onClick = {
                        if (importing) return@OutlinedButton
                        SteamService.isImporting = true
                        zipLauncher.launch(PACK_ZIP_TYPES)
                    },
                    modifier = Modifier
                        .focusRequester(importFocus)
                        .focusOutline(CircleShape),
                ) {
                    if (importing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.themes_sounds_import))
                    }
                }
            }
        }

        item(key = "store_header") { SoundSectionHeader(stringResource(R.string.themes_sounds_store)) }
        item(key = "store_search") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NoExtractOutlinedTextField(
                    value = store.query,
                    onValueChange = { store.query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.themes_sounds_search_hint)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { loadStore(reset = true) }),
                )
                Button(
                    onClick = { loadStore(reset = true) },
                    enabled = !store.loading,
                    modifier = Modifier.focusOutline(CircleShape),
                ) { Text(stringResource(R.string.search)) }
            }
        }
        items(store.items, key = { "store:${it.id}" }) { item ->
            SoundStoreRow(
                item = item,
                installed = item.name.lowercase() in installedNames,
                installing = item.id in store.installing,
                onInstall = { installFromStore(item) },
            )
        }
        item(key = "store_footer") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    store.loading -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    store.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.themes_sounds_store_error, store.error.orEmpty()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Button(
                            onClick = { loadStore(reset = store.items.isEmpty()) },
                            modifier = Modifier.focusOutline(CircleShape),
                        ) { Text(stringResource(R.string.themes_retry)) }
                    }

                    store.loaded && store.items.isEmpty() -> SoundHint(stringResource(R.string.themes_sounds_store_empty))
                    store.loaded && !store.endReached -> OutlinedButton(
                        onClick = { loadStore(reset = false) },
                        modifier = Modifier.focusOutline(CircleShape),
                    ) { Text(stringResource(R.string.themes_load_more)) }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        MenuColorScope {
            AlertDialog(
                onDismissRequest = {
                    deleteTarget = null
                    refocusDelete = target.id
                },
                containerColor = PluviaTheme.dialogContainerColor,
                title = { Text(stringResource(R.string.themes_sounds_delete_title)) },
                text = {
                    DialogSound()
                    Text(stringResource(R.string.themes_delete_message, target.name))
                },
                confirmButton = {
                    TextButton(onClick = {
                        deleteTarget = null
                        focusAfterDelete = target.isMusic
                        runAction { SoundPackManager.delete(target.id) }
                    }) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        deleteTarget = null
                        refocusDelete = target.id
                    }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }
}

private fun percentText(value: Float): String = "${(value * 100).roundToInt()}%"

@Composable
private fun packDetail(pack: InstalledSoundPack): String? = listOfNotNull(
    pack.author?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.themes_by_author, it) },
    pack.version?.takeIf { it.isNotBlank() },
).joinToString(" · ").takeIf { it.isNotEmpty() }

@Composable
private fun SoundSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SoundHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

@Composable
private fun SoundSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = { value ->
                UiSoundCues.toggle(value)
                onCheckedChange(value)
            },
            modifier = Modifier.focusOutline(CircleShape),
        )
    }
}

@Composable
private fun SoundPackRow(
    name: String,
    detail: String?,
    selected: Boolean,
    onSelect: () -> Unit,
    onPreview: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    deleteFocusRequester: FocusRequester? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .clip(shape)
                    .focusOutline(shape)
                    .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    detail?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            onPreview?.let {
                TextButton(
                    onClick = it,
                    modifier = Modifier.focusOutline(CircleShape),
                ) { Text(stringResource(R.string.themes_sounds_preview)) }
            }
            onDelete?.let {
                IconButton(
                    onClick = it,
                    modifier = Modifier
                        .then(if (deleteFocusRequester != null) Modifier.focusRequester(deleteFocusRequester) else Modifier)
                        .focusOutline(CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SoundStoreRow(
    item: StoreTheme,
    installed: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    item.author?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.themes_by_author, it) },
                    item.target?.takeIf { it.isNotBlank() },
                    pluralStringResource(R.plurals.themes_downloads, item.downloads, item.downloads),
                ).joinToString(" · ")
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // One button in every state, so focus stays on the row while it installs and after.
            val idle = !installing && !installed
            Button(
                onClick = { if (idle) onInstall() },
                modifier = Modifier.focusOutline(CircleShape),
                colors = if (idle) {
                    ButtonDefaults.buttonColors()
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary,
                    )
                },
            ) {
                when {
                    installing -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    installed -> {
                        Icon(imageVector = Icons.Default.Done, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.installed))
                    }
                    else -> Text(stringResource(R.string.install))
                }
            }
        }
    }
}
