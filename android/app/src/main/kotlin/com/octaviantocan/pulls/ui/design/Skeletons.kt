package com.octaviantocan.pulls.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/*
 * What a page shows while its first answer is on the way, instead of a spinner and a line of text: the shape of
 * the content with the words left out, so nothing jumps when they arrive. AI work in progress does not use these;
 * it shows its live line.
 */

/** How much of the row's width each skeleton title takes, in turn, so a column of them does not look stamped. */
private val TITLE_WIDTHS = listOf(0.92f, 0.74f, 0.86f, 0.64f, 0.8f)

/** A list's first rows: a number, a title and a meta line, laid out like the Inbox rows they stand in for. */
@Composable
fun RowsSkeleton(modifier: Modifier = Modifier, rows: Int = 6) {
    Column(modifier.fillMaxWidth().semantics { contentDescription = "Loading" }) {
        repeat(rows) { index ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Skeleton(Modifier.width(36.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Skeleton(Modifier.fillMaxWidth(TITLE_WIDTHS[index % TITLE_WIDTHS.size]))
                    Skeleton(Modifier.fillMaxWidth(0.42f))
                }
            }
        }
    }
}
