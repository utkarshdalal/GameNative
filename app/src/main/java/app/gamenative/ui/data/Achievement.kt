package app.gamenative.ui.data

data class Achievement(
    val displayName: String,
    val name: String?,
    val isUnlocked: Boolean,
    val description: String,
    val unlockTimestamp: Int,
    val hidden: Boolean,
    val icon: String,
    val iconGray: String?,
    val progressCurrent: Float? = null,
    val progressMax: Float? = null,
){
    /** True when this achievement tracks partial progress (e.g. 45 / 100). */
    val hasProgress: Boolean
        get() = progressMax != null && progressMax > 0f

    /** (date, time-of-day) of the unlock, both localized; null if never unlocked. */
    fun getFormattedUnlockDateTime(): Pair<String, String>? {
        if (unlockTimestamp == 0) return null
        val locale = java.util.Locale.getDefault()
        val millis = java.util.Date(unlockTimestamp * 1000L)
        val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale).format(millis)
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, locale).format(millis)
        return date to time
    }
}
