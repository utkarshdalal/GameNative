package app.gamenative.ui.data

import androidx.compose.runtime.Composable
import app.gamenative.ui.enums.AppOptionMenuType

data class AppMenuOption(
    val optionType: AppOptionMenuType,
    val onClick: () -> Unit,
    /** Extra controls shown under the option's title in the options panel. */
    val content: (@Composable () -> Unit)? = null,
)
