package app.gamenative.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.theme.CommunityTheme
import app.gamenative.theme.InstalledTheme
import app.gamenative.theme.ShareBlocker
import app.gamenative.theme.ThemeManager
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.ui.theme.MenuColorScope
import app.gamenative.ui.theme.PluviaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val REPORT_REASONS = listOf(
    "inappropriate" to R.string.themes_community_report_inappropriate,
    "copyright" to R.string.themes_community_report_copyright,
    "broken" to R.string.themes_community_report_broken,
    "other" to R.string.themes_community_report_other,
)

@Composable
internal fun ShareThemeDialog(
    theme: InstalledTheme,
    lastAuthor: String,
    onDismiss: () -> Unit,
    onShared: (CommunityTheme, String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isUpdate = theme.sharedFromHere
    var name by rememberSaveable(theme.id) { mutableStateOf(if (theme.builtIn) "" else theme.name) }
    var description by rememberSaveable(theme.id) { mutableStateOf(theme.description.orEmpty()) }
    var author by rememberSaveable(theme.id) {
        mutableStateOf(lastAuthor.ifBlank { if (theme.builtIn) "" else theme.author.orEmpty() })
    }
    var rights by rememberSaveable(theme.id) { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // an update keeps the listing's name, description and author unless the user changes them
    LaunchedEffect(theme.id) {
        val listed = theme.communityId?.takeIf { isUpdate }?.let { ThemeManager.sharedListing(it) } ?: return@LaunchedEffect
        if (name.isBlank()) name = listed.name
        if (description == theme.description.orEmpty()) description = listed.description.orEmpty()
        if (author.isBlank()) author = listed.authorName.orEmpty()
    }

    MenuColorScope {
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        containerColor = PluviaTheme.dialogContainerColor,
        title = {
            Text(stringResource(if (isUpdate) R.string.themes_share_update else R.string.themes_share_title))
        },
        text = {
            val scroll = rememberScrollState()
            // the error line sits below the fields; bring it into view
            LaunchedEffect(error) { if (error != null) scroll.animateScrollTo(scroll.maxValue) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NoExtractOutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.themes_share_name)) },
                    placeholder = { Text(stringResource(R.string.themes_share_name_hint)) },
                    singleLine = true,
                    enabled = !sending,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                NoExtractOutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.themes_share_description)) },
                    minLines = 2,
                    maxLines = 4,
                    enabled = !sending,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                NoExtractOutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text(stringResource(R.string.themes_share_author)) },
                    singleLine = true,
                    enabled = !sending,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusOutline(RoundedCornerShape(8.dp))
                        .toggleable(
                            value = rights,
                            enabled = !sending,
                            role = Role.Checkbox,
                            onValueChange = { rights = it },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = rights, onCheckedChange = null, enabled = !sending)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.themes_share_rights),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !sending && rights && name.isNotBlank(),
                onClick = {
                    sending = true
                    error = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            ThemeManager.shareTheme(theme.id, name.trim(), description.trim(), author.trim())
                        }
                        sending = false
                        result.onSuccess { onShared(it, author.trim()) }
                            .onFailure { error = communityErrorText(ctx, it) }
                    }
                },
            ) {
                if (sending) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(if (isUpdate) R.string.themes_share_update else R.string.themes_share))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) { Text(stringResource(R.string.cancel)) }
        },
    )
    }
}

@Composable
internal fun ShareBlockedDialog(blocker: ShareBlocker, onDismiss: () -> Unit) {
    MenuColorScope {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PluviaTheme.dialogContainerColor,
        title = { Text(stringResource(R.string.themes_share_blocked_title)) },
        text = {
            Text(
                stringResource(
                    when (blocker) {
                        ShareBlocker.NOT_NATIVE -> R.string.themes_share_blocked_not_native
                        ShareBlocker.EMPTY -> R.string.themes_share_blocked_empty
                        ShareBlocker.TOO_LARGE -> R.string.themes_share_blocked_too_large
                    },
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        },
    )
    }
}

@Composable
internal fun ReportThemeDialog(
    theme: CommunityTheme,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reason by rememberSaveable(theme.id) { mutableStateOf(REPORT_REASONS.first().first) }
    var details by rememberSaveable(theme.id) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    MenuColorScope {
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        containerColor = PluviaTheme.dialogContainerColor,
        title = { Text(stringResource(R.string.themes_community_report_title, theme.name)) },
        text = {
            val scroll = rememberScrollState()
            // the error line sits below the fields; bring it into view
            LaunchedEffect(error) { if (error != null) scroll.animateScrollTo(scroll.maxValue) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column(modifier = Modifier.selectableGroup()) {
                    REPORT_REASONS.forEach { (code, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusOutline(RoundedCornerShape(8.dp))
                                .selectable(
                                    selected = reason == code,
                                    enabled = !sending,
                                    role = Role.RadioButton,
                                    onClick = { reason = code },
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = reason == code, onClick = null, enabled = !sending)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(label), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                NoExtractOutlinedTextField(
                    value = details,
                    onValueChange = { details = it },
                    label = { Text(stringResource(R.string.themes_community_report_details)) },
                    minLines = 2,
                    maxLines = 4,
                    enabled = !sending,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !sending,
                onClick = {
                    sending = true
                    error = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            ThemeManager.reportCommunityTheme(theme.id, reason, details.trim().ifBlank { null })
                        }
                        sending = false
                        result.onSuccess { onSent() }.onFailure { error = communityErrorText(ctx, it) }
                    }
                },
            ) {
                if (sending) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.themes_community_report_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) { Text(stringResource(R.string.cancel)) }
        },
    )
    }
}

@Composable
internal fun DeleteCommunityThemeDialog(
    theme: CommunityTheme,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    MenuColorScope {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PluviaTheme.dialogContainerColor,
        title = { Text(stringResource(R.string.themes_community_delete_title)) },
        text = { Text(stringResource(R.string.themes_community_delete_message, theme.name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
    }
}

@Composable
internal fun ThemeSignInNeededDialog(onDismiss: () -> Unit, onSignIn: () -> Unit) {
    MenuColorScope {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PluviaTheme.dialogContainerColor,
        title = { Text(stringResource(R.string.themes_sign_in_title)) },
        text = { Text(stringResource(R.string.themes_sign_in_message)) },
        confirmButton = {
            TextButton(onClick = onSignIn) { Text(stringResource(R.string.gamenative_account_sign_in)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
    }
}
