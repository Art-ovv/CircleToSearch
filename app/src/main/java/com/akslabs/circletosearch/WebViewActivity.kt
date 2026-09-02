/*
 *
 *  * Copyright (C) 2025 AKS-Labs (original author)
 *  *
 *  * This program is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ViewGroup

class WebViewActivity : Activity() {

    private val TAG = "WebViewActivity"
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this).also { this.webView = it }
        setContentView(webView)

        val url = intent.getStringExtra("url")
        if (url == null) {
            Log.e(TAG, "URL is null, finishing activity.")
            finish()
            return
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true // Enable DOM storage
            // Other potentially useful settings for modern web pages
            allowContentAccess = true
            allowFileAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36" // Mobile user agent
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val newUrl = request?.url.toString()
                Log.d(TAG, "shouldOverrideUrlLoading: $newUrl")
                view?.loadUrl(newUrl)
                return true // Return true to indicate that the host application handles the URL
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                val errorMessage = "Error: ${error?.errorCode} - ${error?.description} for ${request?.url}"
                Log.e(TAG, "onReceivedError: $errorMessage")
                // You could display an error message to the user here
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                // Do not mirror arbitrary page console contents into logcat.
                return true
            }
        }
        
        webView.loadUrl(url)
    }

    override fun onDestroy() {
        // A WebView owns renderer, JavaScript and storage resources that are not
        // released merely by destroying the Activity. Detach it first so the
        // renderer cannot retain this Activity through the view hierarchy.
        webView?.let { view ->
            view.stopLoading()
            view.loadUrl("about:blank")
            view.clearHistory()
            view.webChromeClient = null
            view.webViewClient = WebViewClient()
            (view.parent as? ViewGroup)?.removeView(view)
            view.removeAllViews()
            view.destroy()
        }
        webView = null
        super.onDestroy()
    }
}
