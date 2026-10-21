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
import com.octaviantocan.pulls.ui.IncomingLinks
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
        if (com.octaviantocan.pulls.data.NativeEntry.privateConnection(this)) Updates.restore(this)
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
            // Every role of `PullsType` but the numbers' reads `AppFonts`, which follows this; set before the theme so
            // the first frame is already in the chosen face (N10).
            if (com.octaviantocan.pulls.ui.design.AppFonts.selected != settings.appFont) com.octaviantocan.pulls.ui.design.AppFonts.selected = settings.appFont
            PullsTheme(dark = dark, theme = theme, locale = settings.locale) {
                PullsApp(initialTarget = IncomingLinks.read(intent, this@MainActivity), initialAIResult = IncomingLinks.result(intent, this@MainActivity))
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
        // The update notification brings Pully to the front where the reader left it. A link opens over the page on
        // screen, so Back returns there; the intent is kept so a rotation reopens it like a cold start does.
        if (UpdateNotice.handle(this, intent)) return
        if (IncomingLinks.read(intent, this) == null) return
        setIntent(intent)
        IncomingLinks.receive(intent, this)
    }
}
