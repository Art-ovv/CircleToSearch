package com.akslabs.circletosearch.ui

import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient

internal data class SearchWebViewState(val history: Bundle?, val url: String?)

internal fun saveSearchWebView(view: WebView): SearchWebViewState {
    val history = Bundle().takeIf { view.saveState(it) != null }
    return SearchWebViewState(history, view.url)
}

internal fun destroySearchWebView(view: WebView) {
    (view.parent as? ViewGroup)?.removeView(view)
    view.onPause()
    view.stopLoading()
    view.setOnTouchListener(null)
    view.webChromeClient = null
    view.webViewClient = WebViewClient()
    view.removeAllViews()
    view.destroy()
}
