package com.octaviantocan.pulls.ui.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Main-dispatcher UI cache; every path is generated from the existing checked-in XML vector. */
private val vectors = mutableMapOf<String, ImageVector>()

internal fun sharedVector(name: String): ImageVector? =
    vectors[name] ?: createSharedVector(name)?.also { vectors[name] = it }

/** The same canonical Catppuccin XML paths used by the Android file rows. */
@Composable
fun FileIcon(name: String, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val vector = sharedVector(name) ?: checkNotNull(sharedVector("ic_cat_file")) { "Missing canonical file icon." }
    Image(vector, contentDescription = null, modifier = modifier.size(size))
}
