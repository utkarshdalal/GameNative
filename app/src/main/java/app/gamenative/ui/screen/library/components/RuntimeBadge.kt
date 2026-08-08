package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import com.winlator.container.Container

// library badge for runtime=webview entries. wine entries render NOTHING
// (absence is the indicator). icon + short text, palette matches the family-share badge
// in LibraryListCard for visual consistency.
@Composable
internal fun RuntimeBadge(runtime: String) {
    if (runtime != Container.RUNTIME_WEBVIEW) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Language,
            contentDescription = stringResource(R.string.library_runtime_html5),
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = stringResource(R.string.library_runtime_html5),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

// compact icon-only html5 indicator for the grid Status row, where the full RuntimeBadge
// (icon + text) would overflow. matches the Black-circle styling of the Install / Family icons.
@Composable
internal fun Html5RuntimeIcon(runtime: String) {
    if (runtime != Container.RUNTIME_WEBVIEW) return
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Language,
            contentDescription = stringResource(R.string.library_runtime_html5),
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(12.dp),
        )
    }
}
