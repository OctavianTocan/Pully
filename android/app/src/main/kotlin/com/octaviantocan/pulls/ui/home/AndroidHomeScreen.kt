package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.ui.Nav

/** Android supplies native services; the production screen and state are shared. */
@Composable
fun HomeScreen(nav: Nav, ui: HomeUi) {
    val context = LocalContext.current
    val repo = remember { Repo(context) }
    val services = rememberAndroidHomeScreenServices(repo)
    HomeScreen(nav, ui, services)
}
