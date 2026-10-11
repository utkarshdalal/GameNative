package app.gamenative.online

/**
 * Local post-session report for Online install profile games.
 */
data class OnlineSessionReport(
    val appId: String,
    val store: String = "steam",
    val worked: Boolean,
    val failReason: OnlineFailReason? = null,
    val notes: String? = null,
    val reportedAtEpochMs: Long = System.currentTimeMillis(),
) {
    fun toDebugLine(): String {
        val reason = failReason?.label ?: if (worked) "OK" else "No reason"
        val note = notes?.takeIf { it.isNotBlank() }?.let { " | $it" }.orEmpty()
        return "online_report app=$store:$appId worked=$worked reason=$reason$note"
    }
}
