package com.octaviantocan.pulls.ui.settings

import androidx.compose.runtime.Composable

/** A small muted heading above a card: the heading every sheet uses (M7). */
@Composable
fun Group(title: String, content: @Composable () -> Unit) {
    com.octaviantocan.pulls.ui.design.SheetHeading(title)
    content()
}

