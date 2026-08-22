package com.lpsm.player

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** YouTube dentro do LPSM, sem baixar, instalar ou abrir outro aplicativo. */
class YoutubeActivity : AppCompatActivity() {

    companion object {
        private const val YOUTUBE_TV_URL = "https://www.youtube.com/tv"
        private const val YOUTUBE_FALLBACK_URL = "https://m.youtube.com/"
        private const val APP_REFERER = "https://com.lpsm.player/"
    }

    private lateinit var page: View
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var fullscreen: FrameLayout
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fallbackAttempted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube)

        page = findViewById(R.id.youtubePage)
        webView = findViewById(R.id.youtubeWebView)
        progress = findViewById(R.id.youtubeProgress)
        status = findViewById(R.id.youtubeStatus)
        fullscreen = findViewById(R.id.youtubeFullscreen)

        findViewById<View>(R.id.youtubeHome).setOnClickListener {
            fallbackAttempted = false
            loadYoutube(YOUTUBE_TV_URL)
            webView.requestFocus()
        }
        findViewById<View>(R.id.youtubeBack).setOnClickListener { goBackOrClose() }
        findViewById<View>(R.id.youtubeReload).setOnClickListener {
            webView.reload()
            webView.requestFocus()
        }
        findViewById<View>(R.id.youtubeClose).setOnClickListener { finish() }

        configureWebView()
        val restored = savedInstanceState?.let { webView.restoreState(it) } != null
        if (!restored) loadYoutube(YOUTUBE_TV_URL)
        webView.requestFocus()
    }

    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(false)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
                status.text = "Carregando YouTube..."
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
                status.text = "YOUTUBE"
                view.requestFocus()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = !isAllowedYoutubeAddress(request.url)

            @Deprecated("Compatibilidade Android 5")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                !isAllowedYoutubeAddress(Uri.parse(url))

            @Deprecated("Compatibilidade Android 5")
            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                if (!fallbackAttempted && failingUrl?.contains("youtube.com/tv") == true) {
                    fallbackAttempted = true
                    loadYoutube(YOUTUBE_FALLBACK_URL)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                page.visibility = View.GONE
                fullscreen.visibility = View.VISIBLE
                fullscreen.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                view.requestFocus()
            }

            override fun onHideCustomView() {
                hideCustomView()
            }
        }
    }

    private fun loadYoutube(address: String) {
        webView.loadUrl(address, mapOf("Referer" to APP_REFERER))
    }

    private fun isAllowedYoutubeAddress(uri: Uri): Boolean {
        val host = uri.host?.lowercase().orEmpty()
        val allowed = host == "youtube.com" ||
            host.endsWith(".youtube.com") ||
            host == "youtu.be" ||
            host.endsWith(".google.com") ||
            host.endsWith(".googleapis.com") ||
            host.endsWith(".gstatic.com")
        if (!allowed) {
            Toast.makeText(this, "Link externo bloqueado dentro do LPSM.", Toast.LENGTH_SHORT).show()
        }
        return allowed
    }

    private fun hideCustomView(): Boolean {
        val view = customView ?: return false
        fullscreen.removeView(view)
        fullscreen.visibility = View.GONE
        page.visibility = View.VISIBLE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        webView.requestFocus()
        return true
    }

    private fun goBackOrClose() {
        when {
            hideCustomView() -> Unit
            webView.canGoBack() -> webView.goBack()
            else -> finish()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            goBackOrClose()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        hideCustomView()
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearHistory()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
