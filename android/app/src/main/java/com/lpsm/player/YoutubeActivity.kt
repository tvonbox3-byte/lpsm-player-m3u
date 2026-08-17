package com.lpsm.player

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

class YoutubeActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var search: EditText
    private lateinit var go: Button

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
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgentString + " LPSM-TV"
        }
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.isFocusable = true
        web.isFocusableInTouchMode = true

        go.setOnClickListener { performSearch() }
        search.setOnEditorActionListener { _, _, _ ->
            performSearch(); true
        }
        search.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                showKeyboard(); false
            } else if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                hideKeyboard(); web.requestFocus(); true
            } else false
        }

        web.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
                web.goBack(); true
            } else false
        }

        web.loadUrl("https://www.youtube.com/tv")
        web.requestFocus()
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
}
