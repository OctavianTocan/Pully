package com.octaviantocan.pulls.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.core.PrLinks
import com.octaviantocan.pulls.data.ServerConfig
import java.net.HttpURLConnection
import java.net.URL

/** The page on the pulls.review server for a link the phone received. */
fun reviewUrl(config: ServerConfig, raw: String?): String {
    val base = config.url.trimEnd('/')
    if (raw != null && raw.startsWith(base)) return raw
    val path = when (val target = raw?.let(PrLinks::parse)) {
        is OpenTarget.Pull -> "/${target.owner}/${target.repo}/${target.number}"
        is OpenTarget.Compare -> "/${target.owner}/${target.repo}/compare/${target.base}...${target.head}"
        is OpenTarget.Commit -> "/${target.owner}/${target.repo}/commit/${target.sha}"
        is OpenTarget.Branch -> "/branch/${target.name}"
        OpenTarget.Worktree -> "/worktree"
        null -> "/"
    }
    return base + path
}

/**
 * Turns the saved Cloudflare Access service token into the `CF_Authorization` cookie
 * the web app's socket needs. Returns false when the server does not set one.
 */
fun authorizeWebSession(config: ServerConfig): Boolean {
    if (config.accessId.isBlank() || config.accessSecret.isBlank()) return false
    val connection = (URL(config.url.trimEnd('/') + "/").openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 20_000
        readTimeout = 20_000
        setRequestProperty("CF-Access-Client-Id", config.accessId)
        setRequestProperty("CF-Access-Client-Secret", config.accessSecret)
    }
    connection.connect()
    val cookies = connection.headerFields.entries
        .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
        .flatMap { it.value }
    connection.disconnect()
    val manager = CookieManager.getInstance()
    manager.setAcceptCookie(true)
    val host = config.url.trimEnd('/')
    var found = false
    for (cookie in cookies) {
        manager.setCookie(host, cookie)
        if (cookie.startsWith("CF_Authorization=")) found = true
    }
    manager.flush()
    return found
}

@SuppressLint("SetJavaScriptEnabled")
fun reviewWebView(context: android.content.Context, startUrl: String): WebView {
    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mediaPlaybackRequiresUserGesture = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host.orEmpty()
                if (host == "github.com" || host == "www.github.com") {
                    val path = PrLinks.parse(request.url.toString())?.let { reviewUrl(com.octaviantocan.pulls.data.ServerStore.load(context), request.url.toString()) }
                    if (path != null) {
                        view.loadUrl(path)
                        return true
                    }
                }
                return false
            }
        }
        loadUrl(startUrl)
    }
}
