package app.gamenative.ui.component.dialog

import android.content.res.Configuration
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import app.gamenative.ui.theme.PluviaTheme
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GOGWebViewDialog(
    isVisible: Boolean,
    url: String,
    onDismissRequest: () -> Unit,
    onUrlChange: ((String) -> Unit)? = null,
) {
    if (isVisible) {
        var topBarTitle by rememberSaveable { mutableStateOf("GOG Authentication") }
        val startingUrl by rememberSaveable(url) { mutableStateOf(url) }
        var webView: WebView? = remember { null }
        val webViewState = rememberSaveable { Bundle() }

        Dialog(
            onDismissRequest = {
                if (webView?.canGoBack() == true) {
                    webView!!.goBack()
                } else {
                    webViewState.clear()
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
                                IconButton(
                                    onClick = {
                                        webViewState.clear()
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
                        factory = { context ->
                            WebView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )

                                // GOG-specific WebView settings
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    loadWithOverviewMode = true
                                    useWideViewPort = true
                                    builtInZoomControls = true
                                    displayZoomControls = false
                                    setSupportZoom(true)
                                    allowFileAccess = true
                                    allowContentAccess = true
                                    allowFileAccessFromFileURLs = true
                                    allowUniversalAccessFromFileURLs = true
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                                    // GOG-specific user agent (similar to Heroic)
                                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/200.0"
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                        Timber.d("GOG WebView navigating to: $url")
                                        url?.let { currentUrl ->
                                            onUrlChange?.invoke(currentUrl)
                                        }
                                        return super.shouldOverrideUrlLoading(view, url)
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        Timber.d("GOG WebView page finished loading: $url")
                                    }

                                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                                        super.onReceivedError(view, errorCode, description, failingUrl)
                                        Timber.e("GOG WebView error: $errorCode - $description for URL: $failingUrl")
                                    }
                                }

                                webChromeClient = object : WebChromeClient() {
                                    override fun onReceivedTitle(view: WebView?, title: String?) {
                                        title?.let { pageTitle ->
                                            topBarTitle = pageTitle
                                            Timber.d("GOG WebView title: $pageTitle")
                                        }
                                    }

                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        super.onProgressChanged(view, newProgress)
                                        Timber.d("GOG WebView progress: $newProgress%")
                                    }
                                }

                                if (webViewState.size() > 0) {
                                    restoreState(webViewState)
                                } else {
                                    Timber.d("Loading GOG WebView URL: $startingUrl")
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
private fun Preview_GOGWebView() {
    PluviaTheme {
        GOGWebViewDialog(
            isVisible = true,
            url = "https://auth.gog.com/auth?client_id=46899977096215655&redirect_uri=https%3A%2F%2Fembed.gog.com%2Fon_login_success%3Forigin%3Dclient&response_type=code&layout=galaxy",
            onDismissRequest = {
                println("GOG WebView dismissed!")
            },
        )
    }
}
