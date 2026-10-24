package com.octaviantocan.pulls.ui.settings

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.t

/**
 * Signing in to a server behind Cloudflare Access from inside Pully. A fresh install had only the service-token form,
 * so "Use an existing Pully account" stopped at "This server needs access authorization" ("Not entirely sure why this
 * is. Okay, that's not good", recording 23 Oct 12:12, 01:19 to 01:33). This opens the server's own Access login in a
 * page inside Pully; once Access lets the page through, the sign-in's `CF_Authorization` token is kept for that server
 * and sent with every request ([com.octaviantocan.pulls.data.ServerConfig.headers]). Google does not sign in inside
 * an app's page, so the page says to use the emailed code.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun AccessSignInSheet(onSignedIn: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val c = LocalPullsColors.current
    val server = remember { ServerStore.load(context).url.trim().trimEnd('/') }
    val host = remember(server) { Uri.parse(server).host.orEmpty() }
    val signedIn by rememberUpdatedState(onSignedIn)
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(c.base).statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = PullsSpace.x4, vertical = PullsSpace.x2),
                horizontalArrangement = Arrangement.spacedBy(PullsSpace.x3), verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatIcon(Lu.close, t("common.close"), onClose)
                Text(t("connection.accessSignIn"), style = PullsType.lg, color = c.text)
            }
            Text(t("connection.accessHint"), Modifier.padding(horizontal = PullsSpace.x4).padding(bottom = PullsSpace.x3), style = PullsType.sm, color = c.muted)
            AndroidView(
                factory = { ctx ->
                    val cookies = CookieManager.getInstance()
                    cookies.setAcceptCookie(true)
                    // An old sign-in's cookie must not pass for a new one; Cloudflare's own session is kept, so a
                    // second sign-in can go straight through.
                    cookies.setCookie(server, "CF_Authorization=; Max-Age=0; Path=/")
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                if (Uri.parse(url.orEmpty()).host != host) return
                                val token = accessCookie(cookies.getCookie(server)) ?: return
                                ServerStore.saveAccessToken(ctx, token)
                                signedIn()
                            }
                        }
                        loadUrl("$server/")
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
                onRelease = { it.stopLoading(); it.destroy() },
            )
        }
    }
}

/** The `CF_Authorization` value in a `Cookie` header, or null. */
internal fun accessCookie(header: String?): String? = header.orEmpty().split(';').map { it.trim() }
    .firstOrNull { it.startsWith("CF_Authorization=") }?.substringAfter('=')?.takeIf { it.count { ch -> ch == '.' } == 2 }
