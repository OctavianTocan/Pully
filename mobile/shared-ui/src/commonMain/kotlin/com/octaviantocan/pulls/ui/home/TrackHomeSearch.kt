package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay

/** The existing settled-search event records counts/length only, never the query. */
@Composable
internal fun TrackHomeSearch(where: String, query: String, results: Int) {
    val effects = LocalHomeScreenServices.current.effects
    val found by rememberUpdatedState(results)
    val chars = query.trim().length
    LaunchedEffect(where, chars, query.trim()) {
        if (chars == 0) return@LaunchedEffect
        delay(1_500L)
        effects.track("search.run", "where" to where, "results" to found, "chars" to chars)
    }
}
