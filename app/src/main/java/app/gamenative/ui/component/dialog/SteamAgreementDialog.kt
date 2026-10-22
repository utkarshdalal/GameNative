package app.gamenative.ui.component.dialog

import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.ui.theme.PluviaTheme
import kotlinx.coroutines.delay

@Composable
fun SteamAgreementDialog(
    title: String,
    url: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val hasPage = url.isNotBlank()
    val webView = remember { mutableStateOf<WebView?>(null) }
    Dialog(
        onDismissRequest = onDecline,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .then(if (hasPage) Modifier.fillMaxHeight(0.9f) else Modifier.wrapContentHeight()),
            shape = RoundedCornerShape(20.dp),
            color = PluviaTheme.colors.surfaceElevated,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Text(
                    text = stringResource(R.string.steam_agreement_message),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                if (hasPage) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = {
                                WebView(it).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                                    isFocusable = false
                                    settings.setNeedInitialFocus(false)
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                            view?.scrollTo(0, 0)
                                        }
                                    }
                                    loadUrl(url)
                                    webView.value = this
                                }
                            },
                            onRelease = { it.destroy() },
                        )
                    }
                }

                val acceptFocusRequester = remember { FocusRequester() }
                val isWindowFocused = LocalWindowInfo.current.isWindowFocused
                LaunchedEffect(isWindowFocused) {
                    if (isWindowFocused) {
                        delay(100)
                        runCatching { acceptFocusRequester.requestFocus() }
                        webView.value?.isFocusable = true
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDecline) {
                        Text(stringResource(R.string.steam_agreement_decline))
                    }
                    Button(
                        onClick = onAccept,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .focusRequester(acceptFocusRequester),
                    ) {
                        Text(stringResource(R.string.steam_agreement_accept))
                    }
                }
            }
        }
    }
}
