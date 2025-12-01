package app.gamenative.ui.enums

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AvTimer
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Diversity3
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.ui.graphics.vector.ImageVector
import app.gamenative.enums.AppType
import app.gamenative.R
import java.util.EnumSet

enum class AppFilter(
    val code: Int,
    val displayText: String,
    val icon: ImageVector,
) {
    INSTALLED(
        code = 0x01,
        displayText = "Installed",
        icon = Icons.Default.InstallMobile,
    ),
    GAME(
        code = 0x02,
        displayText = "Game",
        icon = Icons.Default.VideogameAsset,
    ),
    APPLICATION(
        code = 0x04,
        displayText = "Application",
        icon = Icons.Default.Computer,
    ),
    TOOL(
        code = 0x08,
        displayText = "Tool",
        icon = Icons.Default.Build,
    ),
    DEMO(
        code = 0x10,
        displayText = "Demo",
        icon = Icons.Default.AvTimer,
    ),
    SHARED(
        code = 0x20,
        displayText = "Family Sharing",
        icon = Icons.Default.Diversity3,
    ),
    // ALPHABETIC(
    //     code = 0x20,
    //     displayText = "Alphabetic",
    //     icon = Icons.Default.SortByAlpha,
    // ),
    ;

    companion object {
        fun getAppType(appFilter: EnumSet<AppFilter>): EnumSet<AppType> {
            val output: EnumSet<AppType> = EnumSet.noneOf(AppType::class.java)
            if (appFilter.contains(GAME)) {
                output.add(AppType.game)
            }
            if (appFilter.contains(APPLICATION)) {
                output.add(AppType.application)
            }
            if (appFilter.contains(TOOL)) {
                output.add(AppType.tool)
            }
            if (appFilter.contains(DEMO)) {
                output.add(AppType.demo)
            }
            return output
        }

        fun fromFlags(flags: Int): EnumSet<AppFilter> {
            val result = EnumSet.noneOf(AppFilter::class.java)
            AppFilter.entries.forEach { appFilter ->
                if (flags and appFilter.code == appFilter.code) {
                    result.add(appFilter)
                }
            }
            return result
        }

        fun toFlags(value: EnumSet<AppFilter>): Int {
            return value.map { it.code }.reduceOrNull { first, second -> first or second } ?: 0
        }
    }
}
