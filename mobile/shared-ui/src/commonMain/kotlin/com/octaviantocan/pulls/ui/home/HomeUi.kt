package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The inbox's tab, chip and search. They live above the screens so a review does not reset them. */
@Stable
class HomeUi(filter: String = "waiting") {
    private var owner: HomeScreenData? = null

    /** Navigation keeps private input; changing account/server authorization clears it. */
    fun bindSource(source: HomeScreenData) {
        if (owner != null && owner !== source) { search = ""; askOpen = false }
        owner = source
    }

    /** 0 Inbox, 1 Tours. */
    var tab by mutableIntStateOf(0)
    var filter by mutableStateOf(filter)
    var showClosed by mutableStateOf(false)
    var search by mutableStateOf("")
    /** The Ask sheet over the inbox is open; it stays open across a pull request opened from it. */
    var askOpen by mutableStateOf(false)
}
