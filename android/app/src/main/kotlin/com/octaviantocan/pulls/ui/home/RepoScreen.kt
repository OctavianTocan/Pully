package com.octaviantocan.pulls.ui.home

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.octaviantocan.pulls.ui.Nav

/** Owner: home worker. Port of `pages-web/gh/[owner]/[repo]/index.vue` (`PullsPage`). */
@Composable
fun RepoScreen(owner: String, repo: String, nav: Nav) {
    Text("$owner/$repo")
}
