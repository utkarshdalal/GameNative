package app.gamenative.ui.screen.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.theme.CommunityException
import app.gamenative.theme.CommunitySort
import app.gamenative.theme.CommunityTheme
import app.gamenative.theme.InstalledTheme
import app.gamenative.theme.ThemeSpec
import app.gamenative.ui.component.NoExtractOutlinedTextField
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

internal class CommunityState {
    var query by mutableStateOf("")
    var sort by mutableStateOf(CommunitySort.POPULAR)
    var mineOnly by mutableStateOf(false)
    val items = mutableStateListOf<CommunityTheme>()
    var page by mutableStateOf(0)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var hasMore by mutableStateOf(false)
    var loaded by mutableStateOf(false)
    val installing = mutableStateListOf<String>()
    val deleting = mutableStateListOf<String>()
    var job: Job? = null

    /** Row whose Delete or Report button should take focus back after a dialog; a row that is gone gives it to a neighbour. */
    var focusRow by mutableStateOf<String?>(null)
}

internal fun communityErrorText(ctx: Context, e: Throwable): String {
    val code = (e as? CommunityException)?.code
    val serverText = e.message?.takeIf { it.isNotBlank() && it != code }
    return when (code) {
        "unauthorized" -> ctx.getString(R.string.themes_community_error_unauthorized)
        "invalid_theme" -> serverText?.let { ctx.getString(R.string.themes_community_error_invalid, it) }
            ?: ctx.getString(R.string.themes_community_error_server)
        "text_rejected" -> ctx.getString(R.string.themes_community_error_text_rejected)
        "too_large" -> ctx.getString(R.string.themes_share_blocked_too_large)
        "rate_limited" -> ctx.getString(R.string.themes_community_error_rate_limited)
        "not_found" -> ctx.getString(R.string.themes_community_error_not_found)
        "network" -> ctx.getString(R.string.themes_community_error_network)
        "terms_required", "terms_outdated" -> ctx.getString(R.string.themes_share_terms_changed)
        "forbidden" -> ctx.getString(R.string.themes_share_forbidden)
        else -> ctx.getString(R.string.themes_community_error_server)
    }
}

@Composable
internal fun CommunityThemesTab(
    state: CommunityState,
    installedByCommunityId: Map<String, InstalledTheme>,
    signedIn: Boolean,
    onSearch: () -> Unit,
    onSortChange: (CommunitySort) -> Unit,
    onMineToggle: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onInstall: (CommunityTheme) -> Unit,
    onReport: (CommunityTheme) -> Unit,
    onDelete: (CommunityTheme) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val secondaryFocus = remember { HashMap<String, FocusRequester>() }
    val actionFocus = remember { HashMap<String, FocusRequester>() }
    val searchFocus = remember { FocusRequester() }
    var lastIndex by remember { mutableStateOf(0) }

    // After a delete or report dialog the window gives focus back late, so ask a few times.
    LaunchedEffect(state.focusRow, state.items.size, state.deleting.size) {
        val id = state.focusRow ?: return@LaunchedEffect
        val index = state.items.indexOfFirst { it.id == id }
        if (index >= 0) lastIndex = index
        val target = when {
            index >= 0 -> secondaryFocus[id]
            state.items.isNotEmpty() -> actionFocus[state.items[lastIndex.coerceAtMost(state.items.size - 1)].id]
            else -> searchFocus
        }
        repeat(COMMUNITY_FOCUS_ATTEMPTS) {
            delay(COMMUNITY_FOCUS_SETTLE_MS)
            runCatching { target?.requestFocus() }
        }
        if (state.focusRow == id && (index < 0 || id !in state.deleting)) state.focusRow = null
    }

    // the results sit under the on-screen keyboard, so searching closes it
    val search = {
        focusManager.clearFocus()
        onSearch()
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NoExtractOutlinedTextField(
                value = state.query,
                onValueChange = { state.query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.themes_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
            )
            Button(
                onClick = search,
                modifier = Modifier
                    .focusRequester(searchFocus)
                    .focusOutline(CircleShape),
            ) { Text(stringResource(R.string.search)) }
        }

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "popular") {
                FilterChip(
                    selected = state.sort == CommunitySort.POPULAR,
                    onClick = { onSortChange(CommunitySort.POPULAR) },
                    label = { Text(stringResource(R.string.themes_community_sort_popular), maxLines = 1) },
                    modifier = Modifier.focusOutline(RoundedCornerShape(8.dp)),
                )
            }
            item(key = "new") {
                FilterChip(
                    selected = state.sort == CommunitySort.NEW,
                    onClick = { onSortChange(CommunitySort.NEW) },
                    label = { Text(stringResource(R.string.themes_community_sort_new), maxLines = 1) },
                    modifier = Modifier.focusOutline(RoundedCornerShape(8.dp)),
                )
            }
            if (signedIn) {
                item(key = "mine") {
                    FilterChip(
                        selected = state.mineOnly,
                        onClick = onMineToggle,
                        label = { Text(stringResource(R.string.themes_community_mine), maxLines = 1) },
                        modifier = Modifier.focusOutline(RoundedCornerShape(8.dp)),
                    )
                }
            }
        }

        when {
            state.items.isEmpty() && state.loading -> CenteredBox {
                CircularProgressIndicator()
            }

            state.items.isEmpty() && state.error != null -> CenteredBox {
                CommunityErrorBlock(message = state.error.orEmpty(), onRetry = onRetry)
            }

            state.items.isEmpty() && state.loaded -> CenteredBox {
                Text(
                    text = stringResource(
                        if (state.mineOnly) R.string.themes_community_mine_empty else R.string.themes_community_empty,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.items.toList(), key = { it.id }) { theme ->
                    CommunityThemeRow(
                        theme = theme,
                        installedTheme = installedByCommunityId[theme.id],
                        installing = theme.id in state.installing,
                        deleting = theme.id in state.deleting,
                        onInstall = { onInstall(theme) },
                        onReport = { onReport(theme) },
                        onDelete = { onDelete(theme) },
                        actionFocus = actionFocus.getOrPut(theme.id) { FocusRequester() },
                        secondaryFocus = secondaryFocus.getOrPut(theme.id) { FocusRequester() },
                    )
                }
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            state.error != null && !state.loading -> CommunityErrorBlock(message = state.error.orEmpty(), onRetry = onRetry)
                            state.loading || state.hasMore -> LoadMoreButton(loading = state.loading, onClick = onLoadMore)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommunityErrorBlock(message: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.themes_community_error, message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier.focusOutline(CircleShape),
        ) { Text(stringResource(R.string.themes_retry)) }
    }
}

@Composable
private fun CommunityThemeRow(
    theme: CommunityTheme,
    installedTheme: InstalledTheme?,
    installing: Boolean,
    deleting: Boolean,
    onInstall: () -> Unit,
    onReport: () -> Unit,
    onDelete: () -> Unit,
    actionFocus: FocusRequester,
    secondaryFocus: FocusRequester,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ThemePreviewSwatch(spec = theme.preview)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = theme.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                theme.authorName?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = stringResource(R.string.themes_by_author, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = listOf(
                        pluralStringResource(R.plurals.themes_downloads, theme.downloads, theme.downloads),
                        stringResource(R.string.themes_community_version, theme.version),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                theme.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (theme.mine && theme.hidden) {
                    Text(
                        text = stringResource(
                            when (theme.hiddenReason) {
                                "reports" -> R.string.themes_community_hidden_reports
                                "copyright" -> R.string.themes_community_hidden_copyright
                                else -> R.string.themes_community_hidden_other
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (theme.hiddenReason == "copyright") CopyrightPolicyButton()
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!deleting) {
                    val hasUpdate = installedTheme?.updateAvailable != null
                    RowActionButton(
                        label = stringResource(if (hasUpdate) R.string.themes_update else R.string.install),
                        onClick = onInstall,
                        modifier = Modifier.focusRequester(actionFocus),
                        busy = installing,
                        doneLabel = if (installedTheme != null && !hasUpdate) stringResource(R.string.installed) else null,
                    )
                }
                // One button in every state, so focus stays on it while the listing is deleted.
                TextButton(
                    onClick = { if (!deleting) (if (theme.mine) onDelete else onReport)() },
                    modifier = Modifier
                        .focusRequester(secondaryFocus)
                        .focusOutline(CircleShape),
                ) {
                    when {
                        theme.mine && deleting -> {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.deleting),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        theme.mine -> Text(
                            text = stringResource(R.string.delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                        else -> Text(stringResource(R.string.themes_community_report))
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemePreviewSwatch(spec: ThemeSpec?) {
    val scheme = MaterialTheme.colorScheme
    val radius = (spec?.cornerRadiusDp ?: 8f).coerceIn(0f, 12f).dp
    val outerShape = RoundedCornerShape(radius)
    val innerShape = RoundedCornerShape(radius / 2)
    Box(
        modifier = Modifier
            .width(112.dp)
            .aspectRatio(16f / 10f)
            .clip(outerShape)
            .background(spec?.background?.let { Color(it) } ?: scheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (spec == null) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
            )
        } else {
            val text = spec.text?.let { Color(it) } ?: scheme.onSurface
            val muted = spec.textMuted?.let { Color(it) } ?: scheme.onSurfaceVariant
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .clip(innerShape)
                    .background((spec.card ?: spec.surface)?.let { Color(it) } ?: scheme.surface)
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(text),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(muted),
                )
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(10.dp)
                        .clip(CircleShape)
                        .background(spec.primary?.let { Color(it) } ?: scheme.primary),
                )
            }
        }
    }
}

private const val COMMUNITY_FOCUS_SETTLE_MS = 150L
private const val COMMUNITY_FOCUS_ATTEMPTS = 4
