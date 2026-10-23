package app.gamenative.ui.component.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.api.CommunityConfigService
import app.gamenative.api.CommunityGame
import app.gamenative.ui.component.NoExtractOutlinedTextField
import kotlinx.coroutines.CancellationException

/**
 * Picks the game in GameNative's game list that a custom game folder contains.
 * Searches run on submit rather than per keystroke: game search shares the community API's
 * request budget with the community config browser.
 */
@Composable
fun CustomGameMatchDialog(
    folderName: String,
    currentMatch: CommunityGame?,
    onMatch: (CommunityGame) -> Unit,
    onRemoveMatch: () -> Unit,
    onDismissRequest: () -> Unit,
    searchGames: suspend (String) -> List<CommunityGame> = CommunityConfigService.shared::searchGames,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf(currentMatch?.name ?: suggestedMatchQuery(folderName)) }
    var searchKey by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf(emptyList<CommunityGame>()) }
    var searched by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(searchKey) {
        val trimmed = query.trim()
        results = emptyList()
        searched = false
        errorMessage = null
        loading = trimmed.isNotEmpty()
        if (trimmed.isEmpty()) return@LaunchedEffect
        try {
            results = searchGames(trimmed)
            searched = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = context.getString(
                R.string.custom_game_match_search_failed,
                e.message ?: context.getString(R.string.community_config_unknown_error),
            )
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.option_match_game)) },
        text = {
            // The dialog has its own window, so its focus manager must be read in here
            val focusManager = LocalFocusManager.current
            val search: () -> Unit = {
                focusManager.clearFocus()
                searchKey++
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.custom_game_match_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (currentMatch != null) {
                    Text(
                        text = stringResource(R.string.custom_game_matched_to, currentMatch.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                NoExtractOutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.search)) },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = search) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search))
                        }
                    },
                    keyboardOptions = KeyboardOptions.Default.copy(
                        imeAction = ImeAction.Search,
                        showKeyboardOnFocus = false,
                    ),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                val error = errorMessage
                when {
                    loading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }

                    error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = search) {
                            Text(stringResource(R.string.community_config_retry))
                        }
                    }

                    results.isEmpty() -> if (searched) {
                        Text(
                            text = stringResource(R.string.custom_game_match_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> Column {
                        results.forEach { game ->
                            ListItem(
                                headlineContent = { Text(game.name) },
                                trailingContent = if (game.id == currentMatch?.id) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else {
                                    null
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onMatch(game) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        },
        dismissButton = if (currentMatch != null) {
            {
                TextButton(onClick = onRemoveMatch) {
                    Text(stringResource(R.string.custom_game_match_remove))
                }
            }
        } else {
            null
        },
    )
}

private val BRACKETED_TAG = Regex("""\[[^\]]*\]|\([^)]*\)|\{[^}]*\}""")
private val VERSION_TAG = Regex(
    """(?<![\p{L}\d])(?:v\d+(?:\.\d+)*|\d+(?:\.\d+)+)[a-z]?(?![\p{L}\d])|\bbuild\s*\d+\b""",
    RegexOption.IGNORE_CASE,
)
private val WORD_SEPARATOR_DOT = Regex("""(?<=\p{L}{2})\.(?=\p{L}{2})""")
private val WHITESPACE = Regex("""\s+""")

/**
 * Search text for a custom game folder name: drops bracketed tags and version numbers and turns
 * underscores and dots between words into spaces, so "Hollow.Knight.v1.5.78 [GOG]" searches for
 * "Hollow Knight". Falls back to the folder name when nothing is left.
 */
internal fun suggestedMatchQuery(folderName: String): String {
    val query = folderName
        .replace(BRACKETED_TAG, " ")
        .replace(VERSION_TAG, " ")
        .replace(WORD_SEPARATOR_DOT, " ")
        .replace('_', ' ')
        .replace(WHITESPACE, " ")
        .trim(' ', '-', '.')
    return query.ifEmpty { folderName.trim() }
}
