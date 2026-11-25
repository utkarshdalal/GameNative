package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.service.store.StoreAdapters
import app.gamenative.service.store.StoreGames
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.ui.component.RingTextButton
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.ThemedAlertDialog
import kotlinx.coroutines.launch

/** Adds a game to a store's library by the id the user types, for stores whose owned list can miss titles. */
@Composable
internal fun StoreAddByIdDialog(
    source: GameSource,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var id by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val add = {
        if (id.isNotBlank() && !adding) {
            adding = true
            error = null
            scope.launch {
                StoreGames.addById(context, source, id)
                    .onSuccess { onDismiss() }
                    .onFailure { error = it.message ?: it.javaClass.simpleName }
                adding = false
            }
        }
    }
    ThemedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.store_add_by_id_title, StoreAdapters.displayName(source))) },
        text = {
            Column {
                Text(stringResource(R.string.store_add_by_id_message))
                Spacer(modifier = Modifier.height(12.dp))
                NoExtractOutlinedTextField(
                    value = id,
                    onValueChange = { id = it.trim() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !adding,
                    label = { Text(stringResource(R.string.store_add_by_id_label)) },
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(stringResource(R.string.store_add_by_id_failed, message)) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                )
            }
        },
        confirmButton = {
            RingTextButton(enabled = id.isNotBlank() && !adding, onClick = { add() }) {
                Text(stringResource(R.string.store_add_by_id_add))
            }
        },
        dismissButton = {
            RingTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        containerColor = PluviaTheme.dialogContainerColor,
        titleContentColor = PluviaTheme.dialogTitleColor,
        textContentColor = PluviaTheme.dialogTextColor,
    )
}
