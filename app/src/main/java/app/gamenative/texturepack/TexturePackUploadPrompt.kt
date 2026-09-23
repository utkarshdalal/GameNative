package app.gamenative.texturepack

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object TexturePackUploadPrompt {

    var pendingAppId: String? by mutableStateOf(null)
        private set

    fun request(appId: String) {
        if (appId.isNotBlank()) pendingAppId = appId
    }

    fun clear() {
        pendingAppId = null
    }
}
