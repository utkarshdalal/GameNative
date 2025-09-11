package app.gamenative.ui.enums

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.ui.graphics.vector.ImageVector

enum class DialogType(val icon: ImageVector? = null) {
    CRASH,
    SUPPORT,
    DISCORD,
    SYNC_CONFLICT,
    SYNC_FAIL,
    MULTIPLE_PENDING_OPERATIONS,
    PENDING_OPERATION_NONE,
    PENDING_UPLOAD,
    PENDING_UPLOAD_IN_PROGRESS,
    APP_SESSION_ACTIVE,
    APP_SESSION_SUSPENDED,

    INSTALL_APP,
    NOT_ENOUGH_SPACE,
    CANCEL_APP_DOWNLOAD,
    DELETE_APP,
    INSTALL_IMAGEFS,
    
    GAME_FEEDBACK,
    SAVE_CONTAINER_CONFIG,

    NONE,

    FRIEND_BLOCK(Icons.Default.Block),
    FRIEND_REMOVE(Icons.Default.PersonRemove),
    FRIEND_FAVORITE(Icons.Default.Favorite),
    FRIEND_UN_FAVORITE,
}
