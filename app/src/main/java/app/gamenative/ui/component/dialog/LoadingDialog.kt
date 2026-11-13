package app.gamenative.ui.component.dialog

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.ThemedDialogSurface
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * @param progress A value between 0 and 1 (inclusive), if the value is below 0 then the bar is
 * displayed as indeterminate
 */
@Composable
fun LoadingDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit = {},
    progress: Float,
    message: String = "Loading...",
) {
    when {
        visible -> {
            Dialog(
                onDismissRequest = onDismissRequest,
            ) {
                val cardColors = CardDefaults.cardColors()
                ThemedDialogSurface(
                    shape = CardDefaults.shape,
                    color = cardColors.containerColor,
                    contentColor = cardColors.contentColor,
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(message)
                        Spacer(modifier = Modifier.height(16.dp))
                        if (progress >= 0) {
                            Text(min(100, (progress * 100.0).roundToInt()).toString() + "%")
                            LinearProgressIndicator(
                                progress = { progress },
                                color = PluviaTheme.progressColor,
                                trackColor = PluviaTheme.sliderTrackColor(ProgressIndicatorDefaults.linearTrackColor),
                            )
                        } else {
                            LinearProgressIndicator(color = PluviaTheme.progressColor, trackColor = PluviaTheme.sliderTrackColor(ProgressIndicatorDefaults.linearTrackColor))
                        }
                    }
                }
            }
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_LoadingDialog() {
    PluviaTheme {
        LoadingDialog(
            visible = true,
            progress = .75f,
        )
    }
}
