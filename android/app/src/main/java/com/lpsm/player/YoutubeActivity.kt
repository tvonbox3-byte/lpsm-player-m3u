package com.lpsm.player

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

/**
 * YouTube em modo quiosque dentro do LPSM.
 * Nenhum link pode abrir navegador, loja ou outro aplicativo.
 * Ideal para TV Boxes alugadas/bloqueadas.
 */
class YoutubeActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var search: EditText
    private lateinit var go: Button

    private val allowedHosts = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "youtube-nocookie.com",
        "www.youtube-nocookie.com"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube)

        web = findViewById(R.id.youtubeWeb)
        search = findViewById(R.id.youtubeSearch)
        go = findViewById(R.id.youtubeGo)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            builtInZoomControls = false
            displayZoomControls = false
            userAgentString =
                "Mozilla/5.0 (Linux; Android 11; Android TV) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return true
                return blockExternalNavigation(uri)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                url: String?
            ): Boolean {
                val uri = runCatching { Uri.parse(url ?: "") }.getOrNull()
                    ?: return true
                return blockExternalNavigation(uri)
            }

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: Bitmap?
            ) {
                super.onPageStarted(view, url, favicon)
                // Nunca entrega foco para barras/links externos.
                web.isFocusable = true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean = false
        }

        web.isFocusable = true
        web.isFocusableInTouchMode = true

        go.setOnClickListener { performSearch() }

        search.setOnEditorActionListener { _, _, _ ->
            performSearch()
            true
        }

        search.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    showKeyboard()
                    false
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    hideKeyboard()
                    web.requestFocus()
                    true
                }
                else -> false
            }
        }

        web.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

            if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
                web.goBack()
                true
            } else {
                false
            }
        }

        // Fica sempre dentro desta Activity. Não dispara ACTION_VIEW.
        web.loadUrl("https://www.youtube.com/tv")
        web.requestFocus()
    }

    private fun blockExternalNavigation(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()

        if (scheme != "http" && scheme != "https") {
            return true
        }

        if (host in allowedHosts || host.endsWith(".youtube.com")) {
            return false
        }

        // Googlevideo/ytimg são recursos, não destinos de navegação.
        // Se aparecerem como navegação principal, bloqueia em vez de abrir fora.
        return true
    }

    private fun performSearch() {
        val q = search.text.toString().trim()
        if (q.isNotEmpty()) {
            hideKeyboard()
            val encoded = java.net.URLEncoder.encode(q, "UTF-8")
            web.loadUrl("https://www.youtube.com/results?search_query=$encoded")
            web.requestFocus()
        }
    }

    private fun showKeyboard() {
        search.showSoftInputOnFocus = true
        search.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(search.windowToken, 0)
        search.showSoftInputOnFocus = false
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.stopLoading()
        web.loadUrl("about:blank")
        web.removeAllViews()
        web.destroy()
        super.onDestroy()
    }
}
