package app.gamenative.ui.enums

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Download
import androidx.compose.ui.graphics.vector.ImageVector
import app.gamenative.R

/**
 * Destinations for Home Screen
 */
enum class HomeDestination(@StringRes val title: Int, val icon: ImageVector) {
    Library(R.string.destination_library, Icons.AutoMirrored.Filled.ViewList),
    Downloads(R.string.destination_downloads, Icons.Filled.Download),
}
