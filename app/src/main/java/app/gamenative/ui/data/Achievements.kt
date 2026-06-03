package app.gamenative.ui.data

import java.text.SimpleDateFormat
import java.util.Date

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
    fun getFormattedUnlockTime(unlockTimestamp: Int): String? {
        val timestamp = unlockTimestamp
        if (timestamp == 0) return null

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        return dateFormat.format(Date(timestamp * 1000L))
    }
}
