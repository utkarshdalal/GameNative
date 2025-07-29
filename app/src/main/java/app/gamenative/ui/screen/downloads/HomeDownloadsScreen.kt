package app.gamenative.ui.screen.downloads

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowWidthSizeClass
import app.gamenative.ui.component.topbar.AccountButton
import app.gamenative.ui.component.topbar.BackButton
import app.gamenative.ui.theme.PluviaTheme

@Composable
fun HomeDownloadsScreen(
    onNavigateRoute: (String) -> Unit,
    onLogout: () -> Unit,
) {
    val onBackPressedDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    DownloadsScreenContent(
        onBack = { onBackPressedDispatcher?.onBackPressed() },
        onNavigateRoute = onNavigateRoute,
        onLogout = onLogout,
    )
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun DownloadsScreenContent(
    onBack: () -> Unit,
    onNavigateRoute: (String) -> Unit,
    onLogout: () -> Unit,
) {
    val snackbarHost = remember { SnackbarHostState() }
    val navigator = rememberListDetailPaneScaffoldNavigator<Long>()

    // Pretty much the same as 'NavigableListDetailPaneScaffold'
    BackHandler(navigator.canNavigateBack(BackNavigationBehavior.PopUntilContentChange)) {
        navigator.navigateBack(BackNavigationBehavior.PopUntilContentChange)
    }

    ListDetailPaneScaffold(
        modifier = Modifier.displayCutoutPadding(),
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        listPane = {
            AnimatedPane {
                DownloadsScreenPane(
                    snackbarHost = snackbarHost,
                    onClick = {
                        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, 1L)
                    },
                    onBack = onBack,
                    onNavigateRoute = onNavigateRoute,
                    onLogout = onLogout,
                )
            }
        },
        detailPane = {
            val value = (navigator.currentDestination?.content ?: -1L)
            DownloadsScreenDetail(
                value = value,
                onBack = {
                    // We're still in Adaptive navigation.
                    navigator.navigateBack()
                },
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsScreenPane(
    snackbarHost: SnackbarHostState,
    onClick: () -> Unit,
    onBack: () -> Unit,
    onNavigateRoute: (String) -> Unit,
    onLogout: () -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = "Downloads") },
                actions = {
                    AccountButton(
                        onNavigateRoute = onNavigateRoute,
                        onLogout = onLogout,
                    )
                },
                navigationIcon = { BackButton(onClick = onBack) },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = {
                Surface(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 8.dp,
                ) {
                    Text(
                        modifier = Modifier.padding(24.dp),
                        text = "\uD83D\uDEA7 Not Available \uD83D\uDEA7",
                    )
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsScreenDetail(
    value: Long,
    onBack: () -> Unit,
) {
    val windowWidth = currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass

    Scaffold(
        topBar = {
            // Show Top App Bar when in Compact or Medium screen space.
            if (windowWidth == WindowWidthSizeClass.COMPACT || windowWidth == WindowWidthSizeClass.MEDIUM) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = "TODO Title",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        BackButton(onClick = onBack)
                    },
                )
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
            contentAlignment = Alignment.Center,
            content = {
                if (value == -1L) {
                    Text("Choose something from Download")
                } else {
                    Text("Hi Download $value")
                }
            },
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(
    device = "spec:width=1920px,height=1080px,dpi=440",
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
) // Odin2 Mini
@Composable
private fun Preview_DownloadsScreenContent() {
    PluviaTheme {
        Surface {
            DownloadsScreenContent(
                onBack = {},
                onNavigateRoute = {},
                onLogout = {},
            )
        }
    }
}
