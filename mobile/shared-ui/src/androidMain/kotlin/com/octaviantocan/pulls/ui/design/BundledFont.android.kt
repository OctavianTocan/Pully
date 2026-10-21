package com.octaviantocan.pulls.ui.design

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import com.octaviantocan.pulls.shared.ui.R

internal actual fun bundledFont(name: String, weight: FontWeight): Font = Font(
    resId = when (name) {
        "geist_regular" -> R.font.geist_regular
        "geist_medium" -> R.font.geist_medium
        "geist_semibold" -> R.font.geist_semibold
        "geist_mono_regular" -> R.font.geist_mono_regular
        "geist_mono_medium" -> R.font.geist_mono_medium
        "mona_sans_regular" -> R.font.mona_sans_regular
        "mona_sans_medium" -> R.font.mona_sans_medium
        "mona_sans_semibold" -> R.font.mona_sans_semibold
        "jetbrains_mono_regular" -> R.font.jetbrains_mono_regular
        "fira_code_regular" -> R.font.fira_code_regular
        "source_code_pro_regular" -> R.font.source_code_pro_regular
        "ibm_plex_mono_regular" -> R.font.ibm_plex_mono_regular
        "roboto_mono_regular" -> R.font.roboto_mono_regular
        else -> error("Unknown bundled font: $name")
    },
    weight = weight,
)
