package app.gamenative.ui.component.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.alorma.compose.settings.ui.base.internal.SettingsTileColors
import com.alorma.compose.settings.ui.base.internal.SettingsTileDefaults
import app.gamenative.ui.component.settings.SettingsTileScaffold

@Composable
fun SettingsCenteredLabel(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    subtitle: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
    colors: SettingsTileColors = SettingsTileDefaults.colors(),
    tonalElevation: Dp = ListItemDefaults.Elevation,
    shadowElevation: Dp = ListItemDefaults.Elevation,
) {
    val decoratedTitle: @Composable () -> Unit = {
        ProvideFontSize(
            fontSize = 20.sp,
        ) {
            title()
        }
    }
    val decoratedSubtitle: @Composable (() -> Unit)? =
        subtitle?.let {
            {
                ProvideFontSize(
                    fontSize = 14.sp,
                ) {
                    subtitle()
                }
            }
        }

    SettingsTileScaffold(
        title = {},
        modifier = modifier,
        enabled = false,
        colors = colors,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                icon?.invoke()
                decoratedTitle()
                decoratedSubtitle?.invoke()
            }
            action?.invoke()
        }
    }
}

@Composable
fun ProvideFontSize(
    fontSize: TextUnit,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize)) {
        content()
    }
}
