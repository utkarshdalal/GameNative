package app.gamenative.data

data class SteamAgreementState(
    val timeAccepted: Long,
    val timeUpdated: Long,
) {
    val needsAcceptance: Boolean
        get() = timeAccepted == 0L || timeAccepted < timeUpdated
}
