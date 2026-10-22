package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.ui.Nav

/** Android supplies native services; the actual repository screen is shared. */
@Composable
fun RepoScreen(owner: String, repo: String, nav: Nav, startOnTours: Boolean = false) {
    val context = LocalContext.current
    val server = remember { Repo(context) }
    val services = rememberAndroidHomeScreenServices(server)
    RepoScreen(owner, repo, nav, startOnTours, services)
}
