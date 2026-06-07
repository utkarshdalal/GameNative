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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
                Text("Plugin warnings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            Text(
                text = if (hasBlockingIssues) {
                    "These plugin files may be missing, disabled, or loading in the wrong order. Fix these before launching the game."
                } else if (assetIssues.isNotEmpty()) {
                    "Some enabled plugins are missing files in the game folder. Apply order can restore them from the mod cache."
                } else {
                    "Review these plugin load order warnings before launching the game."
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
                                text = "Missing masters: ${masters.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        issue.disabledMasters.takeIf { it.isNotEmpty() }?.let { masters ->
                            Text(
                                text = "Disabled masters: ${masters.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        issue.lateMasters.takeIf { it.isNotEmpty() }?.let { masters ->
                            Text(
                                text = "Load before this plugin: ${masters.joinToString(", ")}",
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
                            text = issue.deployedAssetWarning(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (issues.size > 6 || assetIssues.size > 6) {
                TextButton(onClick = { showAllWarnings = !showAllWarnings }) {
                    Text(if (showAllWarnings) "Show fewer warnings" else "Show all warnings")
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
                Text("Plugin load order", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (pluginSearchQuery.isBlank() && filteredPlugins.size > 24) {
                    TextButton(onClick = { showAllPlugins = !showAllPlugins }) {
                        Text(if (showAllPlugins) "Show fewer" else "Show all")
                    }
                }
            }
            Text(
                text = game?.displayName ?: "Bethesda game",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Controls the order Bethesda plugin files load in-game. This is separate from mod file priority. Masters must load before plugins that depend on them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (plugins.isEmpty()) {
                Text("No plugin files found in installed mods.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                NexusModsSearchField(
                    value = pluginSearchQuery,
                    placeholder = "Search plugins",
                    onValueChange = { pluginSearchQuery = it },
                )
                if (pluginSearchQuery.isNotBlank()) {
                    Text(
                        "${filteredPlugins.size} of ${plugins.size} plugin(s) shown",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (visiblePlugins.isEmpty()) {
                    Text("No plugins match your search.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Text(plugin.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                plugin.modName.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            issuesByPlugin[plugin.fileName.lowercase()]?.let { issue ->
                                val warning = when {
                                    issue.missingMasters.isNotEmpty() -> "Missing: ${issue.missingMasters.joinToString(", ")}"
                                    issue.disabledMasters.isNotEmpty() -> "Disabled master: ${issue.disabledMasters.joinToString(", ")}"
                                    issue.lateMasters.isNotEmpty() -> "Load earlier: ${issue.lateMasters.joinToString(", ")}"
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
                                                Text("Fix order")
                                            }
                                        }
                                    }
                                }
                            }
                            assetIssuesByPlugin[plugin.fileName.lowercase()]?.let { issue ->
                                Text(
                                    text = issue.deployedAssetWarning(),
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
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                            }
                            IconButton(
                                onClick = { onMove(plugin, 1) },
                                enabled = index < plugins.lastIndex,
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                            }
                        }
                    }
                }
                if (pluginSearchQuery.isBlank() && !showAllPlugins && filteredPlugins.size > 24) {
                    Text(
                        text = "${filteredPlugins.size - 24} more plugin(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun BethesdaPluginAssetIssue.deployedAssetWarning(): String {
    val pluginMissing = missingFiles.any { it.equals(plugin.fileName, ignoreCase = true) }
    val sidecars = missingFiles.filterNot { it.equals(plugin.fileName, ignoreCase = true) }
    return when {
        pluginMissing && sidecars.isNotEmpty() ->
            "This enabled plugin is missing from the game folder. Apply order can restore it. Related archive files are missing: ${sidecars.joinToString(", ")}"
        pluginMissing ->
            "This enabled plugin is missing from the game folder. Apply order can restore it."
        else ->
            "Related archive files are missing from the game folder: ${sidecars.joinToString(", ")}"
    }
}
