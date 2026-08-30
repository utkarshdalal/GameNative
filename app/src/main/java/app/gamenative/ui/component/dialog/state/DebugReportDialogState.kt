package app.gamenative.ui.component.dialog.state

import androidx.compose.runtime.saveable.mapSaver

data class DebugReportDialogState(
    val visible: Boolean,
    val appId: String = "",
    val reportDir: String = "",
    val gameName: String = "",
    val deviceName: String = "",
    val logSizeBytes: Long = 0L,
    val issueText: String = "",
    val phase: String = PHASE_COMPOSE,
    val threadUrl: String = "",
) {
    companion object {
        const val PHASE_COMPOSE = "compose"
        const val PHASE_SENDING = "sending"
        const val PHASE_SUCCESS = "success"
        const val PHASE_ERROR = "error"

        val Saver = mapSaver(
            save = { state ->
                mapOf(
                    "visible" to state.visible,
                    "appId" to state.appId,
                    "reportDir" to state.reportDir,
                    "gameName" to state.gameName,
                    "deviceName" to state.deviceName,
                    "logSizeBytes" to state.logSizeBytes,
                    "issueText" to state.issueText,
                    "phase" to state.phase,
                    "threadUrl" to state.threadUrl,
                )
            },
            restore = { savedMap ->
                DebugReportDialogState(
                    visible = savedMap["visible"] as Boolean,
                    appId = savedMap["appId"] as String,
                    reportDir = savedMap["reportDir"] as String,
                    gameName = savedMap["gameName"] as String,
                    deviceName = savedMap["deviceName"] as String,
                    logSizeBytes = savedMap["logSizeBytes"] as Long,
                    issueText = savedMap["issueText"] as String,
                    phase = (savedMap["phase"] as String).let { if (it == PHASE_SENDING) PHASE_ERROR else it },
                    threadUrl = savedMap["threadUrl"] as String,
                )
            },
        )
    }
}
