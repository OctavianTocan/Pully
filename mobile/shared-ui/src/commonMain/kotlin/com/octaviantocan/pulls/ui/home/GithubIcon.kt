package com.octaviantocan.pulls.ui.home

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.sharedVector

/** The original checked-in GitHub mark; the common vector generator reads its authoritative XML path. */
@Composable
fun GithubIcon(modifier: Modifier = Modifier, tint: Color = LocalPullsColors.current.text, size: Dp = 18.dp, contentDescription: String? = null) {
    val vector = requireNotNull(sharedVector("ic_carbon_logo_github"))
    Icon(rememberVectorPainter(vector), contentDescription, modifier.size(size), tint = tint)
}
