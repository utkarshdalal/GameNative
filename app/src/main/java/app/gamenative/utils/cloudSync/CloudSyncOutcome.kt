package app.gamenative.utils.cloudSync

import app.gamenative.ui.component.dialog.state.MessageDialogState

/**
 * Result of running cloud sync. [syncCloudSaves] takes care of all sync outcomes;
 * the caller only applies the returned outcome.
 *
 * [Proceed] = sync succeeded (or skipped), caller should continue to launch (e.g. call onSuccess).
 * [ShowDialog] = show this dialog; caller should not launch until user dismisses.
 * [Retry] = caller should retry the launch flow (e.g. call preLaunchApp again with retryCount+1).
 */
sealed class CloudSyncOutcome {
    data object Proceed : CloudSyncOutcome()
    data class ShowDialog(val state: MessageDialogState) : CloudSyncOutcome()
    data object Retry : CloudSyncOutcome()
}
