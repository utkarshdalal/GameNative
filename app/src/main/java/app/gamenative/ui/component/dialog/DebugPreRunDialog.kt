package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.gamenative.R
import app.gamenative.api.SupportApi
import app.gamenative.ui.component.RingButton
import app.gamenative.ui.component.RingTextButton
import app.gamenative.ui.component.focusOnOpen
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.ThemedDialogSurface
import app.gamenative.utils.DebugRunParams

@Composable
fun DebugPreRunDialog(
    visible: Boolean,
    runParams: DebugRunParams? = null,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (visible) {
        val appChat by SupportApi.available
        Dialog(onDismissRequest = onDismiss) {
            ThemedDialogSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                shape = RoundedCornerShape(20.dp),
                color = PluviaTheme.colors.surfaceElevated,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                val bodyScroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .dialogScrollKeys(bodyScroll),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(bodyScroll),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.debug_prerun_title),
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                        Text(
                            text = stringResource(
                                when (appChat) {
                                    true -> R.string.debug_prerun_message_1_app
                                    false -> R.string.debug_prerun_message_1
                                    null -> R.string.debug_prerun_message_1_neutral
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .align(Alignment.Start)
                                .padding(bottom = 8.dp),
                        )
                        Text(
                            text = if (appChat == true) {
                                stringResource(R.string.debug_prerun_message_2_app)
                            } else {
                                stringResource(R.string.debug_prerun_message_2) + " " + stringResource(R.string.debug_trial_note)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.Start)
                                .padding(bottom = 8.dp),
                        )
                        Text(
                            text = stringResource(R.string.debug_prerun_message_3),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .align(Alignment.Start)
                                .padding(bottom = 16.dp),
                        )
                        runParams?.instruction?.let { instruction ->
                            Text(
                                text = stringResource(R.string.debug_prerun_instruction, instruction),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .align(Alignment.Start)
                                    .padding(bottom = 8.dp),
                            )
                        }
                        runParams?.minSeconds?.let { seconds ->
                            val minutes = (seconds + 59) / 60
                            Text(
                                text = pluralStringResource(R.plurals.debug_prerun_min_minutes, minutes, minutes),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .align(Alignment.Start)
                                    .padding(bottom = 16.dp),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        RingTextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.cancel))
                        }
                        RingButton(
                            onClick = onStart,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .focusOnOpen(),
                        ) {
                            Text(stringResource(R.string.debug_offer_confirm))
                        }
                    }
                }
            }
        }
    }
}
