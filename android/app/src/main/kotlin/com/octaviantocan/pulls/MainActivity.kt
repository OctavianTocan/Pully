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
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.ui.Commands
import com.octaviantocan.pulls.ui.PullsApp
import com.octaviantocan.pulls.ui.theme.PullsTheme
import com.octaviantocan.pulls.update.UpdateNotice
import com.octaviantocan.pulls.update.Updates

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = AppSettings.get(this)
        // The process can outlive the screen, so a fresh start shows a hidden update card again; a rotation does not.
        if (savedInstanceState == null) Updates.hidden = false
        // Only a fresh launch from the notification acts on it: not a rotation, and not a reopen from Recents.
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) UpdateNotice.handle(this, intent)
        // Paint the window in the chosen theme before the first frame, so a dark phone does not flash paper.
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        paintWindow(when (settings.theme) { "dark" -> true; "light" -> false; else -> night })
        setContent {
            val dark = when (settings.theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            // The bars follow the app's theme, not the system's: dark icons on the light paper.
            DisposableEffect(dark) {
                paintWindow(dark)
                val clear = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear)
                this@MainActivity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            PullsTheme(dark = dark, locale = settings.locale) {
                PullsApp(initialTarget = readTarget(intent))
            }
        }
    }

    /** The window's own background, behind the first frame and any gap in the layout: the theme's `base`. */
    private fun paintWindow(dark: Boolean) {
        window.setBackgroundDrawable(ColorDrawable(if (dark) DARK_BASE else LIGHT_BASE))
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

    private companion object {
        const val LIGHT_BASE = 0xFFFDFCF9.toInt()
        const val DARK_BASE = 0xFF0C1B1F.toInt()
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
