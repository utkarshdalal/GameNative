package app.gamenative.ui.screen.library.components

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PhotoSizeSelectActual
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.component.OptionListItem
import app.gamenative.ui.component.OptionRadioItem
import app.gamenative.ui.component.OptionSectionHeader
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.enums.SortOption
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.adaptivePanelWidth
import java.util.EnumSet

@Composable
fun LibraryOptionsPanel(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    selectedFilters: EnumSet<AppFilter>,
    onFilterChanged: (AppFilter) -> Unit,
    currentSortOption: SortOption,
    onSortOptionChanged: (SortOption) -> Unit,
    currentView: PaneType,
    onViewChanged: (PaneType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstItemFocusRequester = remember { FocusRequester() }

    BackHandler(enabled = isOpen) {
        onDismiss()
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = isOpen,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(150))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }

        AnimatedVisibility(
            visible = isOpen,
            enter = slideInHorizontally(
                initialOffsetX = { fullWidth -> -fullWidth },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ),
            exit = slideOutHorizontally(
                targetOffsetX = { fullWidth -> -fullWidth },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        ) {
            Surface(
                modifier = Modifier
                    .width(adaptivePanelWidth(300.dp))
                    .fillMaxHeight(),
                shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 24.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.options_panel_title),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.options_panel_close),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 12.dp)
                    ) {
                        OptionSectionHeader(text = stringResource(R.string.options_sort_by))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusGroup()
                                .padding(horizontal = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            SortOption.entries.forEachIndexed { index, option ->
                                OptionRadioItem(
                                    text = stringResource(option.displayTextRes),
                                    selected = currentSortOption == option,
                                    onClick = { onSortOptionChanged(option) },
                                    icon = option.icon(),
                                    focusRequester = if (index == 0) firstItemFocusRequester else remember { FocusRequester() },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        OptionSectionHeader(text = stringResource(R.string.library_app_type))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusGroup()
                                .padding(horizontal = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            AppFilter.entries.forEach { appFilter ->
                                if (appFilter.code !in listOf(0x01, 0x20)) {
                                    OptionListItem(
                                        text = appFilter.displayText,
                                        selected = selectedFilters.contains(appFilter),
                                        onClick = { onFilterChanged(appFilter) },
                                        icon = appFilter.icon,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        OptionSectionHeader(text = stringResource(R.string.library_app_status))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusGroup()
                                .padding(horizontal = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            AppFilter.entries.forEach { appFilter ->
                                if (appFilter.code in listOf(0x01, 0x20)) {
                                    OptionListItem(
                                        text = appFilter.displayText,
                                        selected = selectedFilters.contains(appFilter),
                                        onClick = { onFilterChanged(appFilter) },
                                        icon = appFilter.icon,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        OptionSectionHeader(text = stringResource(R.string.library_layout_title))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusGroup()
                                .padding(horizontal = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            OptionRadioItem(
                                text = stringResource(R.string.library_layout_list),
                                selected = currentView == PaneType.LIST,
                                onClick = { onViewChanged(PaneType.LIST) },
                                icon = Icons.AutoMirrored.Filled.List,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OptionRadioItem(
                                text = stringResource(R.string.library_layout_capsule),
                                selected = currentView == PaneType.GRID_CAPSULE,
                                onClick = { onViewChanged(PaneType.GRID_CAPSULE) },
                                icon = Icons.Default.PhotoAlbum,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OptionRadioItem(
                                text = stringResource(R.string.library_layout_hero),
                                selected = currentView == PaneType.GRID_HERO,
                                onClick = { onViewChanged(PaneType.GRID_HERO) },
                                icon = Icons.Default.PhotoSizeSelectActual,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    LaunchedEffect(isOpen) {
        if (isOpen) {
            try {
                firstItemFocusRequester.requestFocus()
            } catch (_: Exception) {
                // Focus request may fail if composition is not ready
            }
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
    device = "spec:width=1920px,height=1080px,dpi=440,orientation=landscape"
)
@Composable
private fun Preview_LibraryOptionsPanel() {
    val context = LocalContext.current
    PrefManager.init(context)
    PluviaTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Game Library",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }

                LibraryOptionsPanel(
                    isOpen = true,
                    onDismiss = { },
                    selectedFilters = EnumSet.of(AppFilter.GAME),
                    onFilterChanged = { },
                    currentSortOption = SortOption.INSTALLED_FIRST,
                    onSortOptionChanged = { },
                    currentView = PaneType.GRID_HERO,
                    onViewChanged = { },
                )
            }
        }
    }
}

private fun SortOption.icon(): ImageVector = when (this) {
    SortOption.INSTALLED_FIRST -> Icons.Default.Download
    SortOption.NAME_ASC -> Icons.Default.SortByAlpha
    SortOption.NAME_DESC -> Icons.Default.SortByAlpha
    SortOption.RECENTLY_PLAYED -> Icons.Default.Schedule
    SortOption.SIZE_SMALLEST -> Icons.Default.Compress
    SortOption.SIZE_LARGEST -> Icons.Default.Storage
}
