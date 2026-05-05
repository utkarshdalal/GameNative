package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.RecommendationRepository
import app.gamenative.data.RecommendedGame
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.screen.library.AppScreen
import app.gamenative.ui.screen.library.RecommendedGameScreen
import app.gamenative.ui.theme.PluviaTheme
import com.posthog.PostHog
import java.util.EnumSet

@Composable
internal fun LibraryDetailPane(
    libraryItem: LibraryItem?,
    onClickPlay: (Boolean) -> Unit,
    onTestGraphics: () -> Unit,
    onBack: () -> Unit,
) {
    Surface {
        if (libraryItem == null) {
            val listState = rememberLazyGridState()
            val emptyState = remember {
                LibraryState(
                    appInfoList = emptyList(),
                    appInfoSortType = EnumSet.of(AppFilter.GAME),
                )
            }

            LibraryListPane(
                state = emptyState,
                listState = listState,
                currentLayout = PrefManager.libraryLayout,
                onPageChange = {},
                onNavigate = {},
                onRefresh = {},
            )
        } else if (libraryItem.isRecommended) {
            val context = LocalContext.current
            var game by remember(libraryItem.recommendedGameId) {
                mutableStateOf<RecommendedGame?>(null)
            }
            LaunchedEffect(libraryItem.recommendedGameId) {
                game = RecommendationRepository.getCurrentRecommendation(context)
                if (game != null && PrefManager.usageAnalyticsEnabled) {
                    PostHog.capture(
                        event = "recommendation_opened",
                        properties = mapOf(
                            "game_name" to (game?.name ?: ""),
                            "game_id" to (game?.id ?: ""),
                        ),
                    )
                }
            }
            game?.let { rec ->
                RecommendedGameScreen(
                    game = rec,
                    onBack = onBack,
                )
            }
        } else {
            AppScreen(
                libraryItem = libraryItem,
                onClickPlay = onClickPlay,
                onTestGraphics = onTestGraphics,
                onBack = onBack,
            )
        }
    }
}

/***********
 * PREVIEW *
 ***********/

@Preview(uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES or android.content.res.Configuration.UI_MODE_TYPE_NORMAL)
@Preview(device = "spec:width=1920px,height=1080px,dpi=440") // Odin2 Mini
@Composable
private fun Preview_LibraryDetailPane() {
    PrefManager.init(LocalContext.current)
    PluviaTheme {
        LibraryDetailPane(
            libraryItem = LibraryItem(
                appId = "${GameSource.STEAM.name}_${Int.MAX_VALUE}",
                name = "Preview Game",
                iconHash = "",
                gameSource = GameSource.STEAM,
            ),
            onClickPlay = { },
            onTestGraphics = { },
            onBack = { },
        )
    }
}
