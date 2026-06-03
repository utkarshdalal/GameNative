package app.gamenative.ui.data

data class Achievement(
    val displayName: String,
    val name: String?,
    val isUnlocked: Boolean,
    val description: String,
    val unlockTimestamp: Int,
    val hidden: Boolean,
    val icon: String,
    val iconGray: String?
){
    fun getFormattedUnlockTime(): String? {
        if (unlockTimestamp == 0) return null
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return dateFormat.format(java.util.Date(unlockTimestamp * 1000L))
    }
}
