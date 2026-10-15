package com.octaviantocan.pulls.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.octaviantocan.pulls.data.Analytics
import kotlinx.coroutines.delay

/** How long a search must rest before it counts as one search, so typing a word is not a search per letter. */
private const val SEARCH_SETTLES_MS = 1_500L

/**
 * `search.run` for a search field once what is typed has rested: [where] it is (`home`, `files`), how many [results]
 * it found and how many characters it has. Never the words themselves (root `CONTRACTS`, analytics-what-not-how-much).
 */
@Composable
internal fun TrackSearch(where: String, query: String, results: Int) {
    val found by rememberUpdatedState(results)
    val chars = query.trim().length
    LaunchedEffect(where, chars, query.trim()) {
        if (chars == 0) return@LaunchedEffect
        delay(SEARCH_SETTLES_MS)
        Analytics.track("search.run", "where" to where, "results" to found, "chars" to chars)
    }
}
