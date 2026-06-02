package app.gamenative.html5.host

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.html5.Html5OptInService
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.html5_switch_hint),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            enabled = !switching,
            onClick = {
                switching = true
                scope.launch {
                    try {
                        val data = withContext(Dispatchers.IO) {
                            // same as the dialog: wine fields stay put so a flip back restores them.
                            loadContainerData().copy(containerVariant = Container.CONTAINER_VARIANT_HTML5)
                        }
                        if (ContainerUtils.applyToContainerGated(context, appId, data)) visible = false
                    } finally {
                        switching = false
                    }
                }
            },
        ) {
            Text(stringResource(R.string.html5_switch_hint_action))
        }
    }
}
