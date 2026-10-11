package com.octaviantocan.pulls.ui

import android.content.Context
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.core.PrLinks
import com.octaviantocan.pulls.data.DiffLine
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.data.PullsApi
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.data.ServerConfig
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.data.args
import com.octaviantocan.pulls.data.OpenedReview
import com.octaviantocan.pulls.data.SavedReview
import com.octaviantocan.pulls.data.Triage
import com.octaviantocan.pulls.data.asOpened
import com.octaviantocan.pulls.data.asSaved
import com.octaviantocan.pulls.data.asPulls
import com.octaviantocan.pulls.data.asTask
import com.octaviantocan.pulls.update.AppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal sealed interface Screen {
    data object Home : Screen
    data class Review(val target: OpenTarget) : Screen
    data class Diff(val target: OpenTarget, val path: String, val line: Int = 0) : Screen
    data object Settings : Screen
}

@Composable
fun PullsApp(initialTarget: String?) {
    val context = LocalContext.current
    var screen by remember {
        mutableStateOf<Screen>(initialTarget?.let(PrLinks::parse)?.let(Screen::Review) ?: Screen.Home)
    }
    var updateNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        updateNote = withContext(Dispatchers.IO) { checkUpdate(context) }
    }
    when (val current = screen) {
        Screen.Home -> HomeScreen(updateNote, { screen = Screen.Review(it) }, { screen = Screen.Settings })
        is Screen.Review -> ReviewScreen(current.target, { screen = Screen.Home }, { path, line -> screen = Screen.Diff(current.target, path, line) })
        is Screen.Diff -> DiffScreen(current.target, current.path, current.line) { screen = Screen.Review(current.target) }
        Screen.Settings -> SettingsScreen { screen = Screen.Home }
    }
}

internal fun checkUpdate(context: Context): String? = runCatching {
    val config = ServerStore.load(context)
    AppUpdater(context).check(config.url, config.headers(), 1)?.let { "Update ${it.versionName} is ready. ${it.notes}" }
}.getOrNull()
