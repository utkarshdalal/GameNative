package app.gamenative.ui.component.dialog

import android.content.res.Configuration
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.redactUrlForLogging
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthWebViewDialog(
    isVisible: Boolean,
    url: String,
    onDismissRequest: () -> Unit,
    onUrlChange: ((String) -> Unit)? = null,
    onPageFinished: ((url: String, webView: WebView) -> Unit)? = null,
    customWebViewClient: WebViewClient? = null,
) {
    if (isVisible) {
        val defaultTitle = stringResource(R.string.auth_webview_title)
        var topBarTitle by rememberSaveable { mutableStateOf(defaultTitle) }
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
                                    content = { Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(R.string.close)) },
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

                                // OAuth WebView settings (secure defaults for GOG/Epic etc.)
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    loadWithOverviewMode = true
                                    useWideViewPort = true
                                    builtInZoomControls = true
                                    displayZoomControls = false
                                    setSupportZoom(true)
                                    // Secure defaults: no file/content access to limit OAuth surface
                                    allowFileAccess = false
                                    allowContentAccess = false
                                    allowFileAccessFromFileURLs = false
                                    allowUniversalAccessFromFileURLs = false
                                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                }

                                // Use custom WebViewClient if provided, otherwise use default
                                webViewClient = customWebViewClient ?: object : WebViewClient() {
                                    private fun handleUrl(url: String?) {
                                        Timber.d("Auth WebView navigating to: ${redactUrlForLogging(url)}")
                                        url?.let { currentUrl -> onUrlChange?.invoke(currentUrl) }
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        handleUrl(request?.url?.toString())
                                        return super.shouldOverrideUrlLoading(view, request)
                                    }

                                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                        handleUrl(url)
                                        return super.shouldOverrideUrlLoading(view, url)
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        Timber.d("Auth WebView page finished loading: ${redactUrlForLogging(url)}")
                                        if (view != null && url != null) {
                                            onPageFinished?.invoke(url, view)
                                        }
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        errorCode: Int,
                                        description: String?,
                                        failingUrl: String?
                                    ) {
                                        super.onReceivedError(view, errorCode, description, failingUrl)
                                        Timber.e("Auth WebView error: $errorCode - $description for URL: ${redactUrlForLogging(failingUrl)}")
                                    }
                                }

                                webChromeClient = object : WebChromeClient() {
                                    override fun onReceivedTitle(view: WebView?, title: String?) {
                                        title?.let { pageTitle ->
                                            topBarTitle = pageTitle
                                            Timber.d("Auth WebView title: $pageTitle")
                                        }
                                    }

                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        super.onProgressChanged(view, newProgress)
                                        Timber.d("Auth WebView progress: $newProgress%")
                                    }
                                }

                                if (webViewState.size() > 0) {
                                    restoreState(webViewState)
                                } else {
                                    Timber.d("Loading Auth WebView URL: ${redactUrlForLogging(startingUrl)}")
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
                            view.stopLoading()
                            view.webViewClient = WebViewClient()
                            view.webChromeClient = WebChromeClient()
                            view.removeAllViews()
                            view.destroy()
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
private fun Preview_AuthWebView() {
    PluviaTheme {
        AuthWebViewDialog(
            isVisible = true,
            url = "https://auth.gog.com/auth?client_id=46899977096215655&redirect_uri=https%3A%2F%2Fembed.gog.com%2Fon_login_success%3Forigin%3Dclient&response_type=code&layout=galaxy",
            onDismissRequest = {},
        )
    }
}
