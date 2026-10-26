package com.octaviantocan.pulls.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Opens Claude's or OpenAI's sign-in page in a browser sheet over Pully, rising from the bottom with Pully still
 * showing above it, instead of sending the person off to the browser app ("instead of opening the browser, we opened a
 * webview in a bottom sheet", recording 26 Oct 2026, 02:59 to 03:13). It is the browser's own Custom Tab, not a WebView:
 * Google refuses to sign in inside a WebView, and both providers offer Google. A browser without sheets opens it as a
 * full-screen tab over Pully, still inside the app.
 */
internal object ProviderSignInTab {
    private const val REQUEST = 0x5049

    /** Whether a browser sheet took [url]; false when no browser on the phone can show one. */
    fun open(context: Context, url: String, paper: Color): Boolean {
        val activity = context.findActivity() ?: return false
        val browser = CustomTabsClient.getPackageName(context, null) ?: return false
        val height = (activity.resources.displayMetrics.heightPixels * 0.92f).toInt()
        val colors = CustomTabColorSchemeParams.Builder().setToolbarColor(paper.toArgb()).setNavigationBarColor(paper.toArgb()).build()
        val tab = CustomTabsIntent.Builder()
            .setInitialActivityHeightPx(height, CustomTabsIntent.ACTIVITY_HEIGHT_FIXED)
            .setToolbarCornerRadiusDp(16)
            .setDefaultColorSchemeParams(colors)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .setBookmarksButtonEnabled(false)
            .setDownloadButtonEnabled(false)
            .build()
        tab.intent.setPackage(browser).setData(Uri.parse(url))
        return try {
            // A sheet-height tab only opens as a sheet when started for a result.
            @Suppress("DEPRECATION")
            activity.startActivityForResult(tab.intent, REQUEST)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
