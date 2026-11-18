package app.gamenative.html5.host

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.html5.Html5OptInService
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.component.hasThemedButtonLook
import app.gamenative.ui.component.themedButtonContent
import app.gamenative.ui.component.themedButtonFill
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.container.ContainerData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// wine stays the default, so html5 has to be offered somewhere. refreshKey re-checks after Edit Container closes.
@Composable
fun Html5SwitchHint(
    appId: String,
    isInstalled: Boolean,
    refreshKey: Any?,
    modifier: Modifier = Modifier,
    loadContainerData: () -> ContainerData,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var visible by remember(appId) { mutableStateOf(false) }
    var switching by remember(appId) { mutableStateOf(false) }

    LaunchedEffect(appId, isInstalled, refreshKey) {
        val eligible = isInstalled && !GameSource.AMAZON.matches(appId)
        visible = eligible && withContext(Dispatchers.IO) {
            !Html5Routing.isHtml5App(context, appId) && Html5OptInService.isWebViewCapable(appId)
        }
    }
    if (!visible) return

    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = PluviaTheme.shape(8.dp)
    val contentColor = themedButtonContent(isFocused, PluviaTheme.onButtonColor(Color.White))

    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .alpha(if (switching) 0.5f else 1f)
            .clip(shape)
            .background(PluviaTheme.buttonColor(Color.White.copy(alpha = if (isFocused) 0.2f else 0.1f)))
            .then(if (hasThemedButtonLook) Modifier.themedButtonFill(isFocused, shape) else Modifier)
            .focusRing(interactionSource, shape, width = 2.dp)
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                enabled = !switching,
                onClick = {
                    switching = true
                    scope.launch {
                        try {
                            val data = withContext(Dispatchers.IO) {
                                // same as the dialog: wine fields stay put so a flip back restores them.
                                loadContainerData().copy(containerVariant = Container.CONTAINER_VARIANT_HTML5)
                            }
                            if (ContainerUtils.applyToContainerGated(context, appId, data, switchTrigger = "hint")) visible = false
                        } finally {
                            switching = false
                        }
                    }
                },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Icon(
            imageVector = Icons.Default.Bolt,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(R.string.html5_switch_hint),
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}
