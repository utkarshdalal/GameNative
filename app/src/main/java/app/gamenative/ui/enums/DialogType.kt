package app.gamenative.ui.enums

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.ui.graphics.vector.ImageVector
import app.gamenative.ui.theme.DialogDefaultFocus

// defaultFocus: the button a controller user lands on. confirm only where it neither destroys nor overwrites data.
enum class DialogType(val icon: ImageVector? = null, val defaultFocus: DialogDefaultFocus = DialogDefaultFocus.Dismiss) {
    CRASH,
    SUPPORT,
    DISCORD,
    SYNC_CONFLICT,
    EA_SYNC_CONFLICT,
    ROCKSTAR_SYNC_CONFLICT,
    UBISOFT_SYNC_CONFLICT,
    SYNC_FAIL,
    SYNC_IN_PROGRESS,
    MULTIPLE_PENDING_OPERATIONS,
    PENDING_OPERATION_NONE,
    PENDING_UPLOAD,
    PENDING_UPLOAD_IN_PROGRESS,
    APP_SESSION_ACTIVE,
    ACCOUNT_SESSION_ACTIVE,
    APP_SESSION_SUSPENDED,

    INSTALL_APP(defaultFocus = DialogDefaultFocus.Confirm),
    INSTALL_APP_PENDING,
    NOT_ENOUGH_SPACE,
    CANCEL_APP_DOWNLOAD,
    DELETE_APP,
    INSTALL_IMAGEFS(defaultFocus = DialogDefaultFocus.Confirm),
    INSTALL_IMAGEFS_FOR_OPEN_CONTAINER(defaultFocus = DialogDefaultFocus.Confirm),
    UPDATE_VERIFY_CONFIRM(defaultFocus = DialogDefaultFocus.Confirm),
    RESET_CONTAINER_CONFIRM,
    
    GAME_FEEDBACK,
    AI_DEBUG_OFFER,
    SAVE_CONTAINER_CONFIG(defaultFocus = DialogDefaultFocus.Confirm),
    APP_UPDATE,
    EXECUTABLE_NOT_FOUND,
    WORKSHOP_UPDATE_PROMPT(defaultFocus = DialogDefaultFocus.Confirm),
    STEAM_UPDATE_PROMPT(defaultFocus = DialogDefaultFocus.Confirm),

    NONE,

    FRIEND_BLOCK(Icons.Default.Block),
    FRIEND_REMOVE(Icons.Default.PersonRemove),
    FRIEND_FAVORITE(Icons.Default.Favorite),
    FRIEND_UN_FAVORITE,
}
