package app.gamenative.ui.screen.settings

import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.theme.CommunityException
import app.gamenative.theme.CommunityTerms
import app.gamenative.theme.CommunityTheme
import app.gamenative.theme.InstalledTheme
import app.gamenative.theme.ShareBlocker
import app.gamenative.theme.ThemeManager
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.ui.theme.MenuColorScope
import app.gamenative.ui.theme.PluviaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    onDismiss: () -> Unit,
    onShared: (CommunityTheme) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val isUpdate = theme.sharedFromHere
    var name by rememberSaveable(theme.id) { mutableStateOf(if (theme.builtIn) "" else theme.name) }
    var description by rememberSaveable(theme.id) { mutableStateOf(theme.description.orEmpty()) }
    var author by rememberSaveable(theme.id) { mutableStateOf(ThemeManager.shareDisplayName.value) }
    var rights by rememberSaveable(theme.id) { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var listed by remember { mutableStateOf<CommunityTheme?>(null) }
    var terms by remember { mutableStateOf<CommunityTerms?>(null) }
    var termsError by remember { mutableStateOf<String?>(null) }
    var termsAttempt by remember { mutableIntStateOf(0) }
    var showTerms by remember { mutableStateOf(false) }

    // an update keeps the listing's name, description and author unless the user changes them
    LaunchedEffect(theme.id) {
        val found = theme.communityId?.takeIf { isUpdate }?.let { ThemeManager.sharedListing(it) } ?: return@LaunchedEffect
        listed = found
        if (name.isBlank()) name = found.name
        if (description == theme.description.orEmpty()) description = found.description.orEmpty()
        if (author.isBlank()) author = found.authorName.orEmpty()
    }

    LaunchedEffect(termsAttempt) {
        termsError = null
        ThemeManager.communityTerms()
            .onSuccess { terms = it }
            .onFailure { termsError = communityErrorText(ctx, it) }
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
                    label = { Text(stringResource(R.string.themes_share_display_name)) },
                    supportingText = { Text(stringResource(R.string.themes_share_display_name_hint)) },
                    singleLine = true,
                    enabled = !sending,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    terms != null -> {
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
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = rights, onCheckedChange = null, enabled = !sending)
                            Spacer(modifier = Modifier.width(8.dp))
                            TermsAgreementText(onOpenTerms = { showTerms = true })
                        }
                        TextButton(
                            onClick = { showTerms = true },
                            modifier = Modifier.focusOutline(CircleShape),
                        ) { Text(stringResource(R.string.themes_share_terms_read)) }
                    }

                    termsError != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.themes_share_terms_error, termsError.orEmpty()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(
                            onClick = { termsAttempt++ },
                            modifier = Modifier.focusOutline(CircleShape),
                        ) { Text(stringResource(R.string.themes_retry)) }
                    }

                    else -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
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
                enabled = !sending && rights && terms != null && name.isNotBlank(),
                onClick = {
                    val accepted = terms ?: return@TextButton
                    keyboard?.hide()
                    sending = true
                    error = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            ThemeManager.shareTheme(theme.id, name.trim(), description.trim(), author.trim(), accepted.version)
                        }
                        sending = false
                        result.onSuccess { onShared(it) }.onFailure { e ->
                            error = when ((e as? CommunityException)?.code) {
                                "terms_outdated", "terms_required" -> {
                                    rights = false
                                    terms = null
                                    termsAttempt++
                                    ctx.getString(R.string.themes_share_terms_changed)
                                }

                                "forbidden" -> ctx.getString(
                                    if (isUpdate && listed?.hidden != false) {
                                        R.string.themes_share_forbidden_removed
                                    } else {
                                        R.string.themes_share_forbidden
                                    },
                                )

                                else -> communityErrorText(ctx, e)
                            }
                        }
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

    val shownTerms = terms
    if (showTerms && shownTerms != null) {
        CommunityTextDialog(
            title = stringResource(R.string.themes_share_terms_title),
            text = shownTerms.uploadTerms,
            footer = shownTerms.contact?.let { stringResource(R.string.themes_terms_contact, it) },
            onDismiss = { showTerms = false },
        )
    }
}

@Composable
private fun TermsAgreementText(onOpenTerms: () -> Unit) {
    val link = stringResource(R.string.themes_share_terms_link)
    val full = stringResource(R.string.themes_share_terms_agree, link)
    val linkStyle = TextLinkStyles(
        SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline),
    )
    val start = full.indexOf(link)
    val text = buildAnnotatedString {
        if (start < 0) {
            append(full)
            append(" ")
            withLink(LinkAnnotation.Clickable(tag = "terms", styles = linkStyle, linkInteractionListener = { onOpenTerms() })) { append(link) }
        } else {
            append(full.substring(0, start))
            withLink(LinkAnnotation.Clickable(tag = "terms", styles = linkStyle, linkInteractionListener = { onOpenTerms() })) { append(link) }
            append(full.substring(start + link.length))
        }
    }
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

/** Long plain text in a dialog; the D-pad scrolls it while it has focus and moves on at either end. */
@Composable
internal fun CommunityTextDialog(
    title: String,
    text: String,
    footer: String?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val step = with(LocalDensity.current) { 96.dp.toPx() }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { focusRequester.requestFocus() }
    }
    MenuColorScope {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PluviaTheme.dialogContainerColor,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .focusOutline(RoundedCornerShape(8.dp))
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val delta = when (event.key) {
                            Key.DirectionDown -> step
                            Key.DirectionUp -> -step
                            Key.PageDown -> step * 4
                            Key.PageUp -> -step * 4
                            else -> return@onPreviewKeyEvent false
                        }
                        val target = (scroll.value + delta).toInt().coerceIn(0, scroll.maxValue)
                        if (target == scroll.value) return@onPreviewKeyEvent false
                        scope.launch { scroll.animateScrollTo(target) }
                        true
                    }
                    .focusable()
                    .verticalScroll(scroll)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = text, style = MaterialTheme.typography.bodyMedium)
                footer?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.focusOutline(CircleShape),
            ) { Text(stringResource(R.string.close)) }
        },
    )
    }
}

/** Opens the community copyright policy, fetching the terms on first use. */
@Composable
internal fun CopyrightPolicyButton(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<CommunityTerms?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(modifier = modifier) {
        TextButton(
            onClick = {
                if (loading) return@TextButton
                loading = true
                error = null
                scope.launch {
                    ThemeManager.communityTerms()
                        .onSuccess { shown = it }
                        .onFailure { error = communityErrorText(ctx, it) }
                    loading = false
                }
            },
            modifier = Modifier.focusOutline(CircleShape),
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(stringResource(R.string.themes_copyright_policy))
        }
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    shown?.let { t ->
        CommunityTextDialog(
            title = stringResource(R.string.themes_copyright_policy),
            text = t.copyrightPolicy,
            footer = t.contact?.let { stringResource(R.string.themes_terms_contact, it) },
            onDismiss = { shown = null },
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
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = reason == code, onClick = null, enabled = !sending)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(label), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (reason == "copyright") {
                    Text(
                        text = stringResource(R.string.themes_community_report_copyright_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CopyrightPolicyButton()
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
