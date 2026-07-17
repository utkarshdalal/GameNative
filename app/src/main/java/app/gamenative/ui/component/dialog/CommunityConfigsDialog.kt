package app.gamenative.ui.component.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.api.CommunityConfigRun
import app.gamenative.api.CommunityConfigService
import app.gamenative.api.CommunityConfigSort
import app.gamenative.api.CommunityGame
import app.gamenative.api.canonicalCommunityGpu
import app.gamenative.api.communityConfigMatchType
import app.gamenative.utils.BestConfigService
import com.winlator.core.GPUInformation
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class CommunityGpuScope {
    CURRENT_GPU,
    ALL_GPUS,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityConfigsDialog(
    visible: Boolean,
    gameName: String,
    onDismissRequest: () -> Unit,
    onApply: (CommunityConfigRun, String) -> Unit,
    service: CommunityConfigService = CommunityConfigService.shared,
) {
    if (!visible) return

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var detectedGpu by remember(gameName) { mutableStateOf("") }
    var resolvedGame by remember(gameName) { mutableStateOf<CommunityGame?>(null) }
    var sort by remember(gameName) { mutableStateOf(CommunityConfigSort.HIGHEST_RATED) }
    var gpuScope by remember(gameName) { mutableStateOf(CommunityGpuScope.CURRENT_GPU) }
    var runs by remember(gameName) { mutableStateOf(emptyList<CommunityConfigRun>()) }
    var total by remember(gameName) { mutableIntStateOf(0) }
    var currentPage by remember(gameName) { mutableIntStateOf(0) }
    var loading by remember(gameName) { mutableStateOf(true) }
    var loadingMore by remember(gameName) { mutableStateOf(false) }
    var errorMessage by remember(gameName) { mutableStateOf<String?>(null) }
    var selectedRun by remember(gameName) { mutableStateOf<CommunityConfigRun?>(null) }
    var lookupKey by remember(gameName) { mutableIntStateOf(0) }

    LaunchedEffect(visible, gameName, lookupKey) {
        loading = true
        errorMessage = null
        runs = emptyList()
        total = 0
        currentPage = 0
        try {
            val renderer = withContext(Dispatchers.IO) {
                runCatching { GPUInformation.getRenderer(context) }.getOrNull().orEmpty().trim()
            }
            detectedGpu = renderer.takeIf { canonicalCommunityGpu(it).isNotEmpty() }.orEmpty()
            if (detectedGpu.isBlank()) gpuScope = CommunityGpuScope.ALL_GPUS
            val game = service.findGame(gameName)
            if (game == null) {
                errorMessage = context.getString(R.string.community_config_game_not_found, gameName)
                loading = false
            } else {
                resolvedGame = game
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = context.getString(
                R.string.community_config_load_failed,
                error.message ?: context.getString(R.string.community_config_unknown_error),
            )
            loading = false
        }
    }

    LaunchedEffect(visible, resolvedGame?.id, detectedGpu, sort, gpuScope, lookupKey) {
        val game = resolvedGame ?: return@LaunchedEffect
        loading = true
        errorMessage = null
        runs = emptyList()
        total = 0
        currentPage = 0
        try {
            val result = service.fetchConfigs(
                gameId = game.id,
                gpu = detectedGpu.takeIf { gpuScope == CommunityGpuScope.CURRENT_GPU },
                sort = sort,
                page = 0,
            )
            runs = result.runs
            total = result.total
            currentPage = result.page
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = context.getString(
                R.string.community_config_load_failed,
                error.message ?: context.getString(R.string.community_config_unknown_error),
            )
        } finally {
            loading = false
        }
    }

    fun loadMore() {
        val game = resolvedGame ?: return
        if (loadingMore || runs.size >= total) return
        val requestedSort = sort
        val requestedScope = gpuScope
        val requestedGpu = detectedGpu.takeIf { requestedScope == CommunityGpuScope.CURRENT_GPU }
        coroutineScope.launch {
            loadingMore = true
            errorMessage = null
            try {
                val result = service.fetchConfigs(
                    gameId = game.id,
                    gpu = requestedGpu,
                    sort = requestedSort,
                    page = currentPage + 1,
                )
                if (sort == requestedSort && gpuScope == requestedScope && resolvedGame?.id == game.id) {
                    runs = (runs + result.runs).distinctBy { it.id }
                    total = result.total
                    currentPage = result.page
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                errorMessage = context.getString(
                    R.string.community_config_load_failed,
                    error.message ?: context.getString(R.string.community_config_unknown_error),
                )
            } finally {
                loadingMore = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.community_config_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismissRequest) {
                            Icon(Icons.Default.Close, stringResource(R.string.community_config_close))
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                resolvedGame = null
                                lookupKey++
                            },
                            enabled = !loading && !loadingMore,
                        ) {
                            Icon(Icons.Default.Refresh, stringResource(R.string.community_config_refresh))
                        }
                    },
                )
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                CommunityConfigHeader(
                    gameName = resolvedGame?.name ?: gameName,
                    gpuName = detectedGpu,
                    total = total,
                )
                CommunityConfigControls(
                    sort = sort,
                    gpuScope = gpuScope,
                    gpuAvailable = detectedGpu.isNotBlank(),
                    onSortChange = { sort = it },
                    onGpuScopeChange = { gpuScope = it },
                )
                HorizontalDivider()

                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        errorMessage != null && runs.isEmpty() -> CommunityConfigError(
                            message = errorMessage.orEmpty(),
                            onRetry = {
                                resolvedGame = null
                                lookupKey++
                            },
                            modifier = Modifier.align(Alignment.Center),
                        )
                        runs.isEmpty() -> CommunityConfigEmptyState(
                            gpuFiltered = gpuScope == CommunityGpuScope.CURRENT_GPU,
                            onShowAll = { gpuScope = CommunityGpuScope.ALL_GPUS },
                            modifier = Modifier.align(Alignment.Center),
                        )
                        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(
                                items = runs,
                                key = { "${it.id}:${it.device.id}:${it.createdAt}" },
                            ) { run ->
                                CommunityConfigListItem(
                                    run = run,
                                    onClick = { selectedRun = run },
                                )
                                HorizontalDivider()
                            }
                            if (errorMessage != null) {
                                item(key = "pagination-error") {
                                    CommunityConfigError(
                                        message = errorMessage.orEmpty(),
                                        onRetry = ::loadMore,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            } else if (runs.size < total) {
                                item(key = "load-more") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Button(onClick = ::loadMore, enabled = !loadingMore) {
                                            if (loadingMore) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(18.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                            } else {
                                                Icon(Icons.Default.ExpandMore, null)
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Text(stringResource(R.string.community_config_load_more))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedRun?.let { run ->
        val matchType = communityConfigMatchType(detectedGpu, run.device.gpu)
        CommunityConfigPreviewDialog(
            run = run,
            matchType = matchType,
            onDismissRequest = { selectedRun = null },
            onApply = {
                selectedRun = null
                onApply(run, matchType)
            },
        )
    }
}

@Composable
private fun CommunityConfigHeader(
    gameName: String,
    gpuName: String,
    total: Int,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        headlineContent = {
            Text(
                text = gameName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = gpuName.ifBlank { stringResource(R.string.community_config_gpu_unknown) },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            if (total > 0) Text(stringResource(R.string.community_config_result_count, total))
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommunityConfigControls(
    sort: CommunityConfigSort,
    gpuScope: CommunityGpuScope,
    gpuAvailable: Boolean,
    onSortChange: (CommunityConfigSort) -> Unit,
    onGpuScopeChange: (CommunityGpuScope) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val compact = maxWidth < 600.dp
        val content: @Composable (Modifier) -> Unit = { modifier ->
            CommunitySortControl(sort, onSortChange, modifier)
            CommunityGpuControl(gpuScope, gpuAvailable, onGpuScopeChange, modifier)
        }
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                content(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommunitySortControl(
    selected: CommunityConfigSort,
    onSelected: (CommunityConfigSort) -> Unit,
    modifier: Modifier,
) {
    val options = CommunityConfigSort.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected == option,
                onClick = { onSelected(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = {
                    Text(
                        if (option == CommunityConfigSort.HIGHEST_RATED) {
                            stringResource(R.string.community_config_highest_rated)
                        } else {
                            stringResource(R.string.community_config_newest)
                        },
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommunityGpuControl(
    selected: CommunityGpuScope,
    gpuAvailable: Boolean,
    onSelected: (CommunityGpuScope) -> Unit,
    modifier: Modifier,
) {
    val options = CommunityGpuScope.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected == option,
                onClick = { onSelected(option) },
                enabled = option != CommunityGpuScope.CURRENT_GPU || gpuAvailable,
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = {
                    Text(
                        if (option == CommunityGpuScope.CURRENT_GPU) {
                            stringResource(R.string.community_config_this_gpu)
                        } else {
                            stringResource(R.string.community_config_all_gpus)
                        },
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

@Composable
private fun CommunityConfigListItem(
    run: CommunityConfigRun,
    onClick: () -> Unit,
) {
    val performance = buildList {
        add(stringResource(R.string.community_config_rating_value, run.rating))
        run.averageFps?.let {
            add(stringResource(R.string.community_config_fps_value, String.format(Locale.US, "%.1f", it)))
        }
        formatCommunityConfigDate(run.createdAt).takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString(" | ")
    val hardware = listOf(run.device.model, run.device.gpu)
        .filter { it.isNotBlank() }
        .joinToString(" | ")

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        leadingContent = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(run.rating.toString(), style = MaterialTheme.typography.labelMedium)
            }
        },
        headlineContent = {
            Text(
                text = performance,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (hardware.isNotBlank()) {
                    Text(hardware, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (run.notes.isNotBlank()) {
                    Text(
                        text = run.notes,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
    )
}

@Composable
private fun CommunityConfigError(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Text(message, color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry) { Text(stringResource(R.string.community_config_retry)) }
    }
}

@Composable
private fun CommunityConfigEmptyState(
    gpuFiltered: Boolean,
    onShowAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(
                if (gpuFiltered) {
                    R.string.community_config_no_gpu_results
                } else {
                    R.string.community_config_no_results
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (gpuFiltered) {
            Button(onClick = onShowAll) {
                Text(stringResource(R.string.community_config_show_all_gpus))
            }
        }
    }
}

@Composable
private fun CommunityConfigPreviewDialog(
    run: CommunityConfigRun,
    matchType: String,
    onDismissRequest: () -> Unit,
    onApply: () -> Unit,
) {
    val context = LocalContext.current
    var dependencyNames by remember(run.id) { mutableStateOf<List<String>?>(null) }
    var dependencyCheckFailed by remember(run.id) { mutableStateOf(false) }

    LaunchedEffect(run.id, matchType) {
        dependencyNames = null
        dependencyCheckFailed = false
        try {
            dependencyNames = withContext(Dispatchers.IO) {
                BestConfigService.resolveMissingManifestInstallRequests(
                    context = context,
                    configJson = run.config,
                    matchType = matchType,
                    matchedGpu = run.device.gpu,
                ).map { it.entry.name }.distinct()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            dependencyCheckFailed = true
            dependencyNames = emptyList()
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.community_config_details_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (matchType == "fallback_match") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(R.string.community_config_other_gpu_warning),
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                CommunityConfigSectionTitle(stringResource(R.string.community_config_run_details))
                CommunityConfigDetailRow(
                    stringResource(R.string.community_config_rating),
                    stringResource(R.string.community_config_rating_value, run.rating),
                )
                run.averageFps?.let {
                    CommunityConfigDetailRow(
                        stringResource(R.string.community_config_average_fps),
                        stringResource(R.string.community_config_fps_value, String.format(Locale.US, "%.1f", it)),
                    )
                }
                CommunityConfigDetailRow(
                    stringResource(R.string.community_config_device),
                    run.device.model.ifBlank { stringResource(R.string.community_config_unknown) },
                )
                CommunityConfigDetailRow(
                    stringResource(R.string.community_config_gpu),
                    run.device.gpu.ifBlank { stringResource(R.string.community_config_gpu_unknown) },
                )
                run.device.androidVersion.takeIf { it.isNotBlank() }?.let {
                    CommunityConfigDetailRow(stringResource(R.string.community_config_android), it)
                }
                run.appVersion.takeIf { it.isNotBlank() }?.let {
                    CommunityConfigDetailRow(stringResource(R.string.community_config_app_version), it)
                }
                formatCommunityConfigDate(run.createdAt).takeIf { it.isNotBlank() }?.let {
                    CommunityConfigDetailRow(stringResource(R.string.community_config_submitted), it)
                }

                CommunityConfigSectionTitle(stringResource(R.string.community_config_settings))
                communityConfigSummary(run).forEach { (label, value) ->
                    CommunityConfigDetailRow(label, value)
                }

                CommunityConfigSectionTitle(stringResource(R.string.community_config_dependencies))
                when {
                    dependencyNames == null -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    dependencyCheckFailed -> Text(
                        stringResource(R.string.community_config_dependencies_apply_check),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    dependencyNames.orEmpty().isEmpty() -> Text(
                        stringResource(R.string.community_config_no_downloads),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> Text(
                        stringResource(
                            R.string.community_config_downloads_required,
                            dependencyNames.orEmpty().joinToString("\n"),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (run.tags.isNotEmpty()) {
                    CommunityConfigSectionTitle(stringResource(R.string.community_config_tags))
                    Text(run.tags.joinToString(" | "), style = MaterialTheme.typography.bodyMedium)
                }
                if (run.notes.isNotBlank()) {
                    CommunityConfigSectionTitle(stringResource(R.string.community_config_notes))
                    Text(run.notes, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(onClick = onApply) {
                Text(stringResource(R.string.community_config_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun CommunityConfigSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun CommunityConfigDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.38f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.62f),
        )
    }
}

@Composable
private fun communityConfigSummary(run: CommunityConfigRun): List<Pair<String, String>> {
    val context = LocalContext.current
    return buildList {
        fun addValue(label: Int, key: String) {
            run.configString(key).takeIf { it.isNotBlank() }?.let {
                add(context.getString(label) to it)
            }
        }
        addValue(R.string.community_config_container, "containerVariant")
        addValue(R.string.community_config_wine, "wineVersion")
        addValue(R.string.community_config_emulator, "emulator")
        addValue(R.string.community_config_wrapper, "dxwrapper")
        addValue(R.string.community_config_resolution, "screenSize")
        addValue(R.string.community_config_box64_preset, "box64Preset")
        run.configString("graphicsDriverConfig")
            .settingValue("version")
            .takeIf { it.isNotBlank() }
            ?.let { add(context.getString(R.string.community_config_driver) to it) }
    }
}

private fun String.settingValue(key: String): String {
    return split(',', ';')
        .firstOrNull { it.substringBefore('=').trim().equals(key, ignoreCase = true) }
        ?.substringAfter('=', "")
        ?.trim()
        .orEmpty()
}

private fun formatCommunityConfigDate(value: String): String {
    if (value.isBlank()) return ""
    return runCatching {
        OffsetDateTime.parse(value)
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }.getOrDefault(value.substringBefore('T'))
}
