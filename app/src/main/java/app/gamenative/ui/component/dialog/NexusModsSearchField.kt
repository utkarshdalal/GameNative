package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.ui.component.NoExtractOutlinedTextField

private val NexusSearchWhitespaceRegex = Regex("\\s+")

@Composable
internal fun NexusModsSearchField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NoExtractOutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (value.isNotBlank()) {
            {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.nexus_search_clear))
                }
            }
        } else {
            null
        },
    )
}

internal fun matchesNexusSearch(query: String, vararg values: String): Boolean {
    val terms = query.trim().split(NexusSearchWhitespaceRegex).filter { it.isNotBlank() }
    if (terms.isEmpty()) return true
    return terms.all { term -> values.any { it.contains(term, ignoreCase = true) } }
}
