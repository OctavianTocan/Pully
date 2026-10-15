package com.octaviantocan.pulls

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.toArgb
import com.octaviantocan.pulls.data.Analytics
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.ui.Commands
import com.octaviantocan.pulls.ui.PullsApp
import com.octaviantocan.pulls.ui.design.ColorThemes
import com.octaviantocan.pulls.ui.design.PullsColors
import com.octaviantocan.pulls.ui.theme.PullsTheme
import com.octaviantocan.pulls.update.UpdateNotice
import com.octaviantocan.pulls.update.Updates

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Analytics.activityCreated(restored = savedInstanceState != null)
        enableEdgeToEdge()
        val settings = AppSettings.get(this)
        // A newer build the phone already heard of is asked for from the first frame, before the server answers again.
        Updates.restore(this)
        // Only a fresh launch from the notification acts on it: not a rotation, and not a reopen from Recents.
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) UpdateNotice.handle(this, intent)
        // Paint the window in the chosen theme before the first frame, so a dark phone does not flash paper.
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        paintWindow(ColorThemes.of(settings.colorTheme).colors(when (settings.theme) { "dark" -> true; "light" -> false; else -> night }))
        setContent {
            val dark = when (settings.theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            val theme = ColorThemes.of(settings.colorTheme)
            val colors = theme.colors(dark)
            // The bars follow the app's theme, not the system's: dark icons on the light paper. A theme with one look
            // keeps it under Light too, so the bars follow the palette on screen rather than the choice.
            DisposableEffect(colors) {
                paintWindow(colors)
                val clear = android.graphics.Color.TRANSPARENT
                val style = if (colors.dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear)
                this@MainActivity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            PullsTheme(dark = dark, theme = theme, locale = settings.locale) {
                PullsApp(initialTarget = readTarget(intent))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Analytics.foreground()
    }

    /** Leaving the front sends what is queued; a rotation or a recreate is not leaving. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) Analytics.background()
    }

    /** The window's own background, behind the first frame and any gap in the layout: the theme's `base`. */
    private fun paintWindow(colors: PullsColors) {
        window.setBackgroundDrawable(ColorDrawable(colors.base.toArgb()))
    }

    /** Ctrl+K (Cmd+K) opens the command palette from a keyboard, as the web does. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_K && event.repeatCount == 0 && (event.isCtrlPressed || event.isMetaPressed)) {
            Commands.toggle()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The update notification brings Pulls to the front where the reader left it; only a link opens a new page.
        if (UpdateNotice.handle(this, intent)) return
        setIntent(intent)
        recreate()
    }

    private fun readTarget(intent: Intent?): String? {
        if (intent == null) return null
        val shared = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
            else -> intent.dataString
        }
        return shared?.takeIf { com.octaviantocan.pulls.ui.home.HomeLinks.parse(it) != null }
    }
}
