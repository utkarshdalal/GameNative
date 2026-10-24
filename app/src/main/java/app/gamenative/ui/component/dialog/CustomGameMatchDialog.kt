package app.gamenative.ui.component.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
    matchedName: String?,
    onMatch: (CommunityGame) -> Unit,
    onRemoveMatch: () -> Unit,
    onDismissRequest: () -> Unit,
    searchGames: suspend (String) -> List<CommunityGame> = CommunityConfigService.shared::searchGames,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf(matchedName ?: suggestedMatchQuery(folderName)) }
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
            // On a controller, focus starts on the Search button and returns to it whenever the
            // D-pad moves focus in from outside the content, and a search started there moves it on
            // to the first result or Retry. The search field brings up the keyboard when focused, so
            // it only gets focus when the user moves to it. These requests do nothing in touch mode.
            val searchButtonFocus = remember { FocusRequester() }
            var searchButtonFocused by remember { mutableStateOf(false) }
            var fieldFocused by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { runCatching { searchButtonFocus.requestFocus() } }
            // The dialog has its own window, so its keyboard controller must be read in here
            val keyboard = LocalSoftwareKeyboardController.current
            val search: () -> Unit = {
                keyboard?.hide()
                // Otherwise a focused Retry button going away would leave focus to land on the field
                if (!fieldFocused) runCatching { searchButtonFocus.requestFocus() }
                searchKey++
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    // requestFocus() enters with FocusDirection.Enter, so redirecting doesn't recurse
                    .focusProperties {
                        onEnter = {
                            if (requestedFocusDirection != FocusDirection.Enter) {
                                runCatching { searchButtonFocus.requestFocus() }
                            }
                        }
                    }
                    .focusGroup()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.custom_game_match_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (matchedName != null) {
                    Text(
                        text = stringResource(R.string.custom_game_matched_to, matchedName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    NoExtractOutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.search)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }),
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { fieldFocused = it.hasFocus },
                    )
                    // Beside the field, not its trailing icon, which a D-pad can't reach. Centered on
                    // the field's 56dp outline, which sits below the space for its label.
                    IconButton(
                        onClick = search,
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .focusRequester(searchButtonFocus)
                            .onFocusChanged { searchButtonFocused = it.hasFocus },
                    ) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search))
                    }
                }
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
                        val retryFocus = remember { FocusRequester() }
                        LaunchedEffect(error) { if (searchButtonFocused) runCatching { retryFocus.requestFocus() } }
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = search, modifier = Modifier.focusRequester(retryFocus)) {
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
                        val firstResultFocus = remember { FocusRequester() }
                        LaunchedEffect(results) { if (searchButtonFocused) runCatching { firstResultFocus.requestFocus() } }
                        results.forEachIndexed { index, game ->
                            ListItem(
                                headlineContent = { Text(game.name) },
                                trailingContent = if (game.name == matchedName) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else {
                                    null
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (index == 0) Modifier.focusRequester(firstResultFocus) else Modifier)
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
        dismissButton = if (matchedName != null) {
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
private val RELEASE_GROUP = Regex("""(?<=[\p{Ll}\d])-\p{Lu}[\p{Lu}\d]{2,}(?=\s*$)""")
private val DOT = Regex("""\.""")

// Versions need a dot, so "Danganronpa V3" and "Kingdom Hearts HD 2.8" keep their numbers
private val VERSION_TAG = Regex(
    """(?<![\p{L}\d])(?:v\d+(?:\.\d+)+|\d+(?:\.\d+){2,})[a-z]?(?![\p{L}\d])|\bbuild\s*\d+\b""",
    RegexOption.IGNORE_CASE,
)
private val WHITESPACE = Regex("""\s+""")

/**
 * Search text for a custom game folder name: drops bracketed tags, a trailing release group and
 * version numbers, and turns underscores and dots between words into spaces, so
 * "Hollow.Knight.v1.5.78 [GOG]" searches for "Hollow Knight". Falls back to the folder name when
 * nothing is left.
 */
internal fun suggestedMatchQuery(folderName: String): String {
    val separated = folderName
        .replace(BRACKETED_TAG, " ")
        .replace(RELEASE_GROUP, "")
        .replace('_', ' ')
    val query = DOT.replace(separated) { if (isWordSeparatorDot(separated, it.range.first)) " " else "." }
        .replace(VERSION_TAG, " ")
        .replace(WHITESPACE, " ")
        .trim(' ', '-')
    return query.ifEmpty { folderName.trim() }
}

/**
 * False for the dot at [index] when it ends an abbreviation ("Dr. Langeskov", which the search
 * needs) or is in a number ("1.5.78") or an initialism ("S.T.A.L.K.E.R.").
 */
private fun isWordSeparatorDot(text: String, index: Int): Boolean {
    val before = text.getOrNull(index - 1)
    val after = text.getOrNull(index + 1)
    if (after == ' ' && text.getOrNull(index + 2)?.isLetter() == true) return false
    if (before?.isDigit() == true && after?.isDigit() == true) return false
    val singleLetterBefore = before?.isLetter() == true && text.getOrNull(index - 2)?.isLetterOrDigit() != true
    val wordAfter = after?.isLetterOrDigit() == true && text.getOrNull(index + 2)?.isLetterOrDigit() == true
    return !singleLetterBefore || wordAfter
}
