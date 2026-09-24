package app.xavatarwall.ui

import android.annotation.SuppressLint
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private const val TAG = "XAW-WebView"

private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Safari/537.36"

private const val LOGIN_URL = "https://x.com/i/flow/login"

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    statusText: String,
    error: String?,
    busy: Boolean,
    onLoggedIn: () -> Unit
) {
    val webRef = remember { mutableStateOf<WebView?>(null) }
    var desktopMode by remember { mutableStateOf(false) }

    fun applyUserAgent(web: WebView, desktop: Boolean) {
        web.settings.userAgentString = if (desktop) {
            DESKTOP_UA
        } else {
            // 用系统默认 UA，只去掉 WebView 标记，避免被页面判定为“不支持的内嵌浏览器”
            WebSettings.getDefaultUserAgent(web.context)
                .replace("; wv", "")
                .replace(" Version/4.0", "")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("登录 X") },
                actions = {
                    IconButton(
                        onClick = {
                            val web = webRef.value ?: return@IconButton
                            desktopMode = !desktopMode
                            applyUserAgent(web, desktopMode)
                            web.reload()
                        }
                    ) {
                        Icon(Icons.Filled.SwapHoriz, contentDescription = "切换桌面模式")
                    }
                    IconButton(onClick = { webRef.value?.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    FilledTonalButton(
                        onClick = {
                            CookieManager.getInstance().flush()
                            onLoggedIn()
                        },
                        enabled = !busy,
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Text(if (busy) "检测中…" else "我登录好了")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView.setWebContentsDebuggingEnabled(true)
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.setSupportMultipleWindows(true)
                        settings.loadsImagesAutomatically = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        applyUserAgent(this, false)

                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString().orEmpty()
                                Log.d(TAG, "navigate -> $url")
                                // http/https 交给 WebView 自己加载，其它协议（intent:// 等）忽略
                                return !(url.startsWith("http://") || url.startsWith("https://"))
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onCreateWindow(
                                view: WebView,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: Message
                            ): Boolean {
                                // 把新窗口请求改到主 WebView 里打开，否则点击会“没反应”
                                val popup = WebView(view.context)
                                popup.settings.javaScriptEnabled = true
                                popup.settings.domStorageEnabled = true
                                CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)
                                popup.webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        v: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val url = request?.url?.toString().orEmpty()
                                        if (url.startsWith("http")) {
                                            view.loadUrl(url)
                                        }
                                        return true
                                    }
                                }
                                (resultMsg.obj as? WebView.WebViewTransport)?.webView = popup
                                resultMsg.sendToTarget()
                                return true
                            }

                            override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                                Log.d(TAG, "console: ${cm.message()} @${cm.lineNumber()}")
                                return true
                            }
                        }

                        loadUrl(LOGIN_URL)
                        webRef.value = this
                    }
                }
            )
        }
    }
}
