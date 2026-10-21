package com.octaviantocan.pulls.ui.design

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight

/** Loads the same checked-in font bytes through each host's resource system. */
internal expect fun bundledFont(name: String, weight: FontWeight): Font
