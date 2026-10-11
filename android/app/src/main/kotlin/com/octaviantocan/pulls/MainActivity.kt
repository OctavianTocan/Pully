package com.octaviantocan.pulls

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import com.octaviantocan.pulls.core.PrLinks
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.ui.PullsApp
import com.octaviantocan.pulls.ui.theme.PullsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = AppSettings.get(this)
        setContent {
            val dark = when (settings.theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            PullsTheme(dark = dark, locale = settings.locale) {
                PullsApp(initialTarget = readTarget(intent))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
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
        return shared?.takeIf { PrLinks.parse(it) != null }
    }
}
