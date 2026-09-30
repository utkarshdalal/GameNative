@file:OptIn(ExperimentalFoundationApi::class)

package app.gamenative.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Labelled card showing [label] over either a [value] string or arbitrary [content].
 * [focusableForNavigation] makes it a D-pad focus stop; [onClick] makes it clickable. Either adds the [focusRing].
 */
@Composable
fun InfoCard(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    statusColor: Color? = null,
    isCompact: Boolean = false,
    focusableForNavigation: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    LaunchedEffect(isFocused) {
        if (isFocused) bringIntoViewRequester.bringIntoView()
    }

    val interactive = when {
        // No ripple; the focusRing shows focus and a ripple would bleed past the rounded shape.
        onClick != null -> Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        )
        focusableForNavigation -> Modifier.focusable(interactionSource = interactionSource)
        else -> Modifier
    }

    Surface(
        modifier = modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .then(interactive)
            .focusRing(interactionSource, shape, width = 2.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(if (isCompact) 14.dp else 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Only constrain the label when the chevron shares the row; otherwise let it wrap.
                val hasChevron = onClick != null
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    maxLines = if (hasChevron) 1 else Int.MAX_VALUE,
                    overflow = if (hasChevron) TextOverflow.Ellipsis else TextOverflow.Clip,
                    modifier = if (hasChevron) Modifier.weight(1f, fill = false) else Modifier,
                )
                // Chevron hints the card is tappable.
                if (onClick != null) {
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            if (content != null) {
                content()
            } else if (value != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (statusColor != null) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(statusColor, CircleShape),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Text(
                        text = value,
                        style = if (isCompact) {
                            MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                        } else {
                            MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        },
                        color = if (statusColor != null) statusColor else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
