package app.gamenative.ui.component.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alorma.compose.settings.ui.base.internal.LocalSettingsGroupEnabled
import com.alorma.compose.settings.ui.base.internal.SettingsTileColors
import com.alorma.compose.settings.ui.base.internal.SettingsTileDefaults
import com.alorma.compose.settings.ui.base.internal.SettingsTileScaffold
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.stringResource
import app.gamenative.R

@Composable
fun SettingsListDropdownSearchable(
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalSettingsGroupEnabled.current,
    value: Int,
    items: List<String>,
    itemMuted: List<Boolean>? = null,
    fallbackDisplay: String = "",
    onItemSelected: (Int) -> Unit,
    title: @Composable () -> Unit,
    subtitle: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    colors: SettingsTileColors = SettingsTileDefaults.colors(),
    tonalElevation: Dp = ListItemDefaults.Elevation,
    shadowElevation: Dp = ListItemDefaults.Elevation,
    action: @Composable (() -> Unit)? = null,
) {
    var isDropdownExpanded by remember { mutableStateOf(false) }

    // Keep a handle on the anchor tile so we can return focus to it when the menu
    // closes. Without this, dismissing the popup leaves focus cleared and D-pad /
    // controller navigation has nowhere to go.
    val focusRequester = remember { FocusRequester() }

    // Focus handle for the first item in the list. On a controller we seed focus
    // here (not the search field) so D-pad down immediately walks the drivers and
    // the soft keyboard never pops up. Touch users can still tap the search field.
    val listFocusRequester = remember { FocusRequester() }

    // 🔥 NEW: search state
    var query by remember { mutableStateOf("") }

    // 🔥 NEW: filtered list
    val filteredItems = remember(query, items) {
        items.mapIndexed { index, text -> index to text }
            .filter { it.second.contains(query, ignoreCase = true) }
    }

    val selectedText =
        if (value >= 0 && value < items.size) items[value] else fallbackDisplay

    SettingsTileScaffold(
        modifier = Modifier
            .focusRequester(focusRequester)
            .clickable(
                enabled = enabled,
                onClick = {
                    isDropdownExpanded = true
                    query = "" // reset search
                },
            )
            .then(modifier),
        enabled = enabled,
        title = title,
        subtitle = {
            if (subtitle != null) {
                Column {
                    ProvideTextStyle(value = LocalTextStyle.current.merge(TextStyle(fontStyle = FontStyle.Italic))) {
                        subtitle()
                    }
                    Text(
                        text = selectedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontWeight = FontWeight.Bold),
                    )
                }
            } else {
                Text(
                    text = selectedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontWeight = FontWeight.Bold),
                )
            }
        },
        icon = icon,
        colors = colors,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
    ) {
        DropdownMenu(
            expanded = isDropdownExpanded,
            onDismissRequest = {
                isDropdownExpanded = false
                focusRequester.requestFocus()
            },
        ) {

            // When the menu opens, land focus on the first item rather than the
            // search field so a controller can scroll the list right away (and the
            // soft keyboard stays down). Re-seed if the filter empties the current row.
            LaunchedEffect(isDropdownExpanded) {
                if (isDropdownExpanded) {
                    runCatching { listFocusRequester.requestFocus() }
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    // D-pad down from the search field jumps to the first result. We
                    // request the item's focus directly (rather than moveFocus, which
                    // leaked focus to the screen behind the popup); leaving the field
                    // also drops the soft keyboard so there's nothing left to dismiss.
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            runCatching { listFocusRequester.requestFocus() }
                            true
                        } else {
                            false
                        }
                    },
                placeholder = { Text(text = stringResource(R.string.settings_interface_settingslistdropdownsearchable_searchlabel)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                // Don't pop the soft keyboard when the field merely gains focus
                // (e.g. D-pad / controller navigation). It still shows on tap.
                keyboardOptions = KeyboardOptions.Default.copy(showKeyboardOnFocus = false),
            )

            filteredItems.forEachIndexed { position, (index, text) ->
                val isMuted = itemMuted?.getOrNull(index) == true
                val textColor = if (isMuted) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
                DropdownMenuItem(
                    modifier = if (position == 0) {
                        Modifier.focusRequester(listFocusRequester)
                    } else {
                        Modifier
                    },
                    enabled = enabled,
                    text = { Text(text = text, color = textColor) },
                    onClick = {
                        onItemSelected(index)
                        isDropdownExpanded = false
                        focusRequester.requestFocus()
                    },
                )
            }
        }
        Row {
            Icon(
                modifier = Modifier.align(Alignment.CenterVertically),
                imageVector = if (isDropdownExpanded) {
                    Icons.Filled.ArrowDropUp
                } else {
                    Icons.Filled.ArrowDropDown
                },
                contentDescription = "Dropdown arrow",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (action != null) {
                Spacer(modifier.width(16.dp))
                action()
            }
        }
    }
}
