package app.gamenative.ui.component.dialog

import android.content.res.Configuration
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.ui.component.RingIconButton
import app.gamenative.ui.theme.PluviaTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewDialog(
    isVisible: Boolean,
    url: String,
    onDismissRequest: () -> Unit,
) {
    if (isVisible) {
        var topBarTitle by rememberSaveable { mutableStateOf("GameNative Web View") }
        val startingUrl by rememberSaveable(url) { mutableStateOf(url) }
        var webView: WebView? = remember { null } // WebView class.
        val webViewState = rememberSaveable { Bundle() } // WebView state for lifecycle events.

        Dialog(
            onDismissRequest = {
                if (webView?.canGoBack() == true) {
                    webView!!.goBack()
                } else {
                    webViewState.clear() // Clear the state when we're done.
                    onDismissRequest()
                }
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false,
            ),
            content = {
                Scaffold(
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = {
                                Text(
                                    text = topBarTitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            navigationIcon = {
                                RingIconButton(
                                    onClick = {
                                        webViewState.clear() // Clear the state when we're done.
                                        onDismissRequest()
                                    },
                                    content = { Icon(imageVector = Icons.Default.Close, null) },
                                )
                            },
                        )
                    },
                ) { paddingValues ->
                    AndroidView(
                        modifier = Modifier.padding(paddingValues),
                        factory = {
                            WebView(it).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )

                                webViewClient = WebViewClient()
                                webChromeClient = object : WebChromeClient() {
                                    override fun onReceivedTitle(view: WebView?, title: String?) {
                                        title?.let { pageTitle -> topBarTitle = pageTitle }
                                    }
                                }

                                if (webViewState.size() > 0) {
                                    restoreState(webViewState)
                                } else {
                                    loadUrl(startingUrl)
                                }
                                webView = this
                            }
                        },
                        update = {
                            webView = it
                        },
                        onRelease = { view ->
                            view.saveState(webViewState)
                        },
                    )
                }
            },
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview
@Composable
private fun Preview_WebView() {
    PluviaTheme {
        WebViewDialog(
            isVisible = true,
            url = "https://github.com/utkarshdalal/GameNative",
            onDismissRequest = {
                println("WE CAN GO BACK!")
            },
        )
    }
}
