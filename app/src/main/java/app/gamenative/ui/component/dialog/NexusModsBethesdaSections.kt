package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.mods.BethesdaGame
import app.gamenative.mods.BethesdaPlugin
import app.gamenative.mods.BethesdaPluginAssetIssue
import app.gamenative.mods.BethesdaPluginDependencyIssue
@Composable
internal fun BethesdaPluginDiagnosticsSection(
    issues: List<BethesdaPluginDependencyIssue>,
    assetIssues: List<BethesdaPluginAssetIssue>,
) {
    var showAllWarnings by remember(issues.size, assetIssues.size) { mutableStateOf(false) }
    val hasBlockingIssues = issues.hasBlockingPluginIssues()
    val visibleIssues = if (showAllWarnings) issues else issues.take(6)
    val visibleAssetIssues = if (showAllWarnings) assetIssues else assetIssues.take(6)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (hasBlockingIssues) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.nexus_plugin_warnings_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            Text(
                text = if (hasBlockingIssues) {
                    stringResource(R.string.nexus_plugin_warnings_blocking_description)
                } else if (assetIssues.isNotEmpty()) {
                    stringResource(R.string.nexus_plugin_warnings_missing_files_description)
                } else {
                    stringResource(R.string.nexus_plugin_warnings_general_description)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (hasBlockingIssues) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            visibleIssues.forEach { issue ->
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(issue.plugin.fileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        issue.missingMasters.takeIf { it.isNotEmpty() }?.let { masters ->
                            Text(
                                text = stringResource(R.string.nexus_missing_masters, masters.joinToString(", ")),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        issue.disabledMasters.takeIf { it.isNotEmpty() }?.let { masters ->
                            Text(
                                text = stringResource(R.string.nexus_disabled_masters, masters.joinToString(", ")),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        issue.lateMasters.takeIf { it.isNotEmpty() }?.let { masters ->
                            Text(
                                text = stringResource(R.string.nexus_load_before_plugin, masters.joinToString(", ")),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            visibleAssetIssues.forEach { issue ->
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(issue.plugin.fileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = issue.deployedAssetWarningText(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (issues.size > 6 || assetIssues.size > 6) {
                TextButton(onClick = { showAllWarnings = !showAllWarnings }) {
                    Text(if (showAllWarnings) stringResource(R.string.nexus_show_fewer_warnings) else stringResource(R.string.nexus_show_all_warnings))
                }
            }
        }
    }
}

@Composable
internal fun BethesdaPluginsSection(
    game: BethesdaGame?,
    plugins: List<BethesdaPlugin>,
    issues: List<BethesdaPluginDependencyIssue>,
    assetIssues: List<BethesdaPluginAssetIssue>,
    onToggle: (BethesdaPlugin) -> Unit,
    onMove: (BethesdaPlugin, Int) -> Unit,
    onFixOrder: (BethesdaPlugin, List<String>) -> Unit,
) {
    var showAllPlugins by remember(plugins.size) { mutableStateOf(false) }
    var pluginSearchQuery by remember { mutableStateOf("") }
    val issuesByPlugin = remember(issues) { issues.associateBy { it.plugin.fileName.lowercase() } }
    val assetIssuesByPlugin = remember(assetIssues) { assetIssues.associateBy { it.plugin.fileName.lowercase() } }
    val filteredPlugins = plugins.filter { plugin ->
        matchesNexusSearch(
            pluginSearchQuery,
            plugin.fileName,
            plugin.modName.orEmpty(),
            "priority ${plugin.priority}",
        )
    }
    val visiblePlugins = if (showAllPlugins || pluginSearchQuery.isNotBlank()) filteredPlugins else filteredPlugins.take(24)
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.nexus_plugin_load_order_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (pluginSearchQuery.isBlank() && filteredPlugins.size > 24) {
                    TextButton(onClick = { showAllPlugins = !showAllPlugins }) {
                        Text(if (showAllPlugins) stringResource(R.string.nexus_show_fewer) else stringResource(R.string.nexus_show_all))
                    }
                }
            }
            Text(
                text = game?.displayName ?: stringResource(R.string.nexus_bethesda_game),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.nexus_plugin_load_order_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (plugins.isEmpty()) {
                Text(stringResource(R.string.nexus_no_plugin_files), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                NexusModsSearchField(
                    value = pluginSearchQuery,
                    placeholder = stringResource(R.string.nexus_search_plugins),
                    onValueChange = { pluginSearchQuery = it },
                )
                if (pluginSearchQuery.isNotBlank()) {
                    Text(
                        stringResource(R.string.nexus_plugins_shown, filteredPlugins.size, plugins.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (visiblePlugins.isEmpty()) {
                    Text(stringResource(R.string.nexus_no_plugins_match), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                visiblePlugins.forEach { plugin ->
                    val index = plugins.indexOfFirst { it.fileName == plugin.fileName }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Checkbox(
                            checked = plugin.enabled,
                            onCheckedChange = { onToggle(plugin) },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(plugin.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                plugin.modName.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            issuesByPlugin[plugin.fileName.lowercase()]?.let { issue ->
                                val warning = when {
                                    issue.missingMasters.isNotEmpty() -> stringResource(R.string.nexus_missing_short, issue.missingMasters.joinToString(", "))
                                    issue.disabledMasters.isNotEmpty() -> stringResource(R.string.nexus_disabled_master_short, issue.disabledMasters.joinToString(", "))
                                    issue.lateMasters.isNotEmpty() -> stringResource(R.string.nexus_load_earlier_short, issue.lateMasters.joinToString(", "))
                                    else -> ""
                                }
                                if (warning.isNotBlank()) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            warning,
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (issue.hasBlockingIssue()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (issue.lateMasters.isNotEmpty()) {
                                            TextButton(onClick = { onFixOrder(plugin, issue.lateMasters) }) {
                                                Text(stringResource(R.string.nexus_fix_order))
                                            }
                                        }
                                    }
                                }
                            }
                            assetIssuesByPlugin[plugin.fileName.lowercase()]?.let { issue ->
                                Text(
                                    text = issue.deployedAssetWarningText(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            IconButton(
                                onClick = { onMove(plugin, -1) },
                                enabled = index > 0,
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.nexus_move_up))
                            }
                            IconButton(
                                onClick = { onMove(plugin, 1) },
                                enabled = index < plugins.lastIndex,
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.nexus_move_down))
                            }
                        }
                    }
                }
                if (pluginSearchQuery.isBlank() && !showAllPlugins && filteredPlugins.size > 24) {
                    Text(
                        text = stringResource(R.string.nexus_more_plugins, filteredPlugins.size - 24),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BethesdaPluginAssetIssue.deployedAssetWarningText(): String {
    val pluginMissing = missingFiles.any { it.equals(plugin.fileName, ignoreCase = true) }
    val sidecars = missingFiles.filterNot { it.equals(plugin.fileName, ignoreCase = true) }
    return when {
        pluginMissing && sidecars.isNotEmpty() ->
            stringResource(R.string.nexus_plugin_missing_with_sidecars, sidecars.joinToString(", "))
        pluginMissing ->
            stringResource(R.string.nexus_plugin_missing_file)
        else ->
            stringResource(R.string.nexus_plugin_missing_sidecars, sidecars.joinToString(", "))
    }
}
