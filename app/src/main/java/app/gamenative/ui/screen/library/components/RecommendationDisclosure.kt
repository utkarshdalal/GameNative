package app.gamenative.ui.screen.library.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import app.gamenative.PrefManager
import app.gamenative.R
import com.posthog.PostHog

@Composable
fun RecommendationDisclosureDialog(
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) {
        if (PrefManager.usageAnalyticsEnabled) PostHog.capture(event = "rec_disclosure_shown")
    }
    AlertDialog(
        onDismissRequest = {
            if (PrefManager.usageAnalyticsEnabled) PostHog.capture(event = "rec_disclosure_declined")
            onDismiss()
        },
        title = { Text(text = stringResource(R.string.rec_disclosure_title)) },
        text = { Text(text = stringResource(R.string.rec_disclosure_body)) },
        confirmButton = {
            TextButton(onClick = {
                if (PrefManager.usageAnalyticsEnabled) PostHog.capture(event = "rec_disclosure_allowed")
                onContinue()
            }) {
                Text(text = stringResource(R.string.rec_disclosure_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (PrefManager.usageAnalyticsEnabled) PostHog.capture(event = "rec_disclosure_declined")
                onDismiss()
            }) {
                Text(text = stringResource(R.string.rec_disclosure_not_now))
            }
        },
    )
}
