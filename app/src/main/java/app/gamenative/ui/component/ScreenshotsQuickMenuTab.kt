package app.gamenative.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.theme.PluviaTheme
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import java.text.DateFormat
import java.util.Date

/** Number of recent screenshots shown inline in the pause menu; the rest are reachable via "View all". */
private const val RECENT_COUNT = 5

@Composable
fun ScreenshotsQuickMenuTab(
    appId: String,
    refreshKey: Int,
    onTakeScreenshot: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenViewer: (index: Int) -> Unit,
    firstItemFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val all by rememberScreenshots(appId, refreshKey)
    val recent = remember(all) { all.take(RECENT_COUNT) }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AccentActionRow(
            title = stringResource(R.string.screenshot_take),
            icon = Icons.Default.PhotoCamera,
            accentColor = PluviaTheme.colors.accentPurple,
            onClick = onTakeScreenshot,
            modifier = if (firstItemFocusRequester != null) {
                Modifier.focusRequester(firstItemFocusRequester)
            } else {
                Modifier
            },
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))

        if (all.isEmpty()) {
            Text(
                text = stringResource(R.string.screenshots_empty),
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        } else {
            recent.forEachIndexed { index, item ->
                val rowShape = RoundedCornerShape(8.dp)
                val interaction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .clip(rowShape)
                        .focusRing(interaction, rowShape)
                        .clickable(
                            interactionSource = interaction,
                            indication = ripple(),
                        ) { onOpenViewer(index) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(110.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp)),
                    ) {
                        CoilImage(
                            imageModel = { item.file },
                            imageOptions = ImageOptions(),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Text(
                        text = dateFormat.format(Date(item.dateTakenMillis)),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            AccentActionRow(
                title = stringResource(R.string.screenshots_view_all),
                icon = Icons.Default.PhotoLibrary,
                accentColor = PluviaTheme.colors.accentPurple,
                onClick = onOpenGallery,
            )
        }
    }
}
