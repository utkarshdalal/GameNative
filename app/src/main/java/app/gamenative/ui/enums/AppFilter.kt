package app.gamenative.ui.enums

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AvTimer
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Diversity3
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Stars
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.annotation.StringRes
import app.gamenative.enums.AppType
import app.gamenative.R
import java.util.EnumSet

enum class AppFilter(
    val code: Int,
    @param:StringRes val displayTextRes: Int,
    val icon: ImageVector,
) {
    INSTALLED(
        code = 0x01,
        displayTextRes = R.string.app_filter_installed,
        icon = Icons.Default.InstallMobile,
    ),
    GAME(
        code = 0x02,
        displayTextRes = R.string.app_filter_game,
        icon = Icons.Default.VideogameAsset,
    ),
    APPLICATION(
        code = 0x04,
        displayTextRes = R.string.app_filter_application,
        icon = Icons.Default.Computer,
    ),
    TOOL(
        code = 0x08,
        displayTextRes = R.string.app_filter_tool,
        icon = Icons.Default.Build,
    ),
    DEMO(
        code = 0x10,
        displayTextRes = R.string.app_filter_demo,
        icon = Icons.Default.AvTimer,
    ),
    SHARED(
        code = 0x20,
        displayTextRes = R.string.library_family_shared,
        icon = Icons.Default.Diversity3,
    ),
    COMPATIBLE(
        code = 0x40,
        displayTextRes = R.string.library_compatible,
        icon = Icons.Rounded.Verified,
    ),
    EXPIRED(
        code = 0x80,
        displayTextRes = R.string.filter_expired,
        icon = Icons.Default.HourglassEmpty,
    ),
    PLAYABLE(
        code = 0x100,
        displayTextRes = R.string.filter_playable,
        icon = Icons.Rounded.Speed,
    ),
    FIVE_STAR(
        code = 0x200,
        displayTextRes = R.string.filter_five_star_device,
        icon = Icons.Rounded.Star,
    ),
    FIVE_STAR_GPU(
        code = 0x800,
        displayTextRes = R.string.filter_five_star_gpu,
        icon = Icons.Rounded.Stars,
    ),
    PROVEN_GPU(
        code = 0x400,
        displayTextRes = R.string.filter_proven_gpu,
        icon = Icons.Rounded.SportsEsports,
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
