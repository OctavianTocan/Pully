package com.octaviantocan.pulls.ui.design

import androidx.compose.ui.graphics.vector.ImageVector

/** Main-dispatcher UI cache; every path is generated from the existing checked-in XML vector. */
private val vectors = mutableMapOf<String, ImageVector>()

internal fun sharedVector(name: String): ImageVector? =
    vectors[name] ?: createSharedVector(name)?.also { vectors[name] = it }
