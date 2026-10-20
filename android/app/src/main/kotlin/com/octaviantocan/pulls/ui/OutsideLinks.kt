package com.octaviantocan.pulls.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.UriHandler
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Toaster
import com.octaviantocan.pulls.ui.home.HomeLinks

/**
 * Opens a link anywhere but in Pully: the GitHub app when it is there and takes the page, otherwise the phone's browser.
 * Pully takes github.com pull request links itself (`android-github-links-open-in-pulls`), so a plain VIEW intent for
 * one came straight back to Pully and nothing happened ("the GitHub link in the bottom sheet doesn't open GitHub because
 * Pully grabs it so nothing happens": Tavi, 20 Oct).
 */
object OutsideLinks {
    const val GITHUB_APP = "com.github.android"

    /** Whether some app other than Pully took [url]. [browserOnly] also excludes native GitHub and link-specific apps. */
    fun open(context: Context, url: String, browserOnly: Boolean = false): Boolean {
        if (url.isBlank()) return false
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pm = context.packageManager
        val takers = pm.queryIntentActivities(view, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        val browser = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        val browsers = pm.queryIntentActivities(probe, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }
        for (app in order(takers, browser, browsers, context.packageName, browserOnly)) {
            try {
                context.startActivity(Intent(view).setPackage(app))
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }

    /**
     * Who to hand a link to, first to last: the GitHub app if it takes the page, the default browser, any other browser,
     * then anything else that takes it. Never Pully, and never Android's own chooser, which is no app.
     * With [browserOnly], only the default and other browsers are eligible.
     */
    internal fun order(takers: List<String>, defaultBrowser: String?, browsers: List<String>, own: String, browserOnly: Boolean = false): List<String> =
        (if (browserOnly) listOfNotNull(defaultBrowser) + browsers
        else listOfNotNull(GITHUB_APP.takeIf { it in takers }, defaultBrowser) + browsers + takers)
            .filter { it != own && it != "android" }
            .filter { !browserOnly || it != GITHUB_APP }
            .distinct()
}

/** Opens a link on GitHub, outside Pully, and says so when no app on the phone can. */
@Composable
fun rememberOpenOnGithub(): (String) -> Unit {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    return remember(context, toaster) { { url -> openOutside(context, toaster, url) } }
}

/** Opens a canonical PR page in an explicitly chosen browser, bypassing app-link capture by Pully or GitHub. */
@Composable
fun rememberOpenInBrowser(): (String) -> Unit {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    return remember(context, toaster) { { url ->
        if (url.isNotBlank() && !OutsideLinks.open(context, url, browserOnly = true)) toaster.show("No browser here opens that link", cue = Cue.Error)
    } }
}

internal fun openOutside(context: Context, toaster: Toaster, url: String) {
    if (url.isNotBlank() && !OutsideLinks.open(context, url)) toaster.show("No app here opens that link", cue = Cue.Error)
}

/**
 * What a link tapped inside Pully does, in a description, a comment or an answer: a GitHub pull request, commit or compare
 * link opens in Pully, on the thing it points at, exactly as it would from another app; every other link opens outside.
 */
internal class PullyUriHandler(private val context: Context, private val toaster: Toaster) : UriHandler {
    override fun openUri(uri: String) {
        if (HomeLinks.opensInPully(uri)) IncomingLinks.open(uri) else openOutside(context, toaster, uri)
    }
}
