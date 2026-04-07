package app.gamenative.ui.data

import android.content.Context
import app.gamenative.R

enum class CloudSaveStatus { CHECKING, DOWNLOADING, UPLOADING, UP_TO_DATE, PENDING_DOWNLOAD, FAILED, CONFLICT, OFFLINE, PENDING_UPLOAD;

    val isActive: Boolean get() = this == CHECKING || this == DOWNLOADING || this == UPLOADING
}

fun CloudSaveStatus.toDisplayString(context: Context): String = context.getString(
    when (this) {
        CloudSaveStatus.CHECKING -> R.string.cloud_saves_checking
        CloudSaveStatus.DOWNLOADING -> R.string.cloud_saves_downloading
        CloudSaveStatus.UPLOADING -> R.string.cloud_saves_uploading
        CloudSaveStatus.UP_TO_DATE -> R.string.cloud_saves_up_to_date
        CloudSaveStatus.PENDING_DOWNLOAD -> R.string.cloud_saves_pending_download
        CloudSaveStatus.FAILED -> R.string.cloud_saves_failed
        CloudSaveStatus.CONFLICT -> R.string.cloud_saves_conflict
        CloudSaveStatus.OFFLINE -> R.string.cloud_saves_offline
        CloudSaveStatus.PENDING_UPLOAD -> R.string.cloud_saves_pending_upload
    },
)
