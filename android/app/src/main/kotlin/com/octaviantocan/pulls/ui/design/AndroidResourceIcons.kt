package com.octaviantocan.pulls.ui.design

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val iconIds = mutableMapOf<String, Int>()

@SuppressLint("DiscouragedApi")
fun phIconId(context: Context, name: String): Int = iconIds.getOrPut(name) {
    val clean = name.removePrefix("i-ph:").removePrefix("i-ph-").replace('-', '_')
    context.resources.getIdentifier("ic_ph_$clean", "drawable", context.packageName)
}

/** A Catppuccin file or folder icon (the web `DisplayFileIcon`). */
@Composable
fun FileIcon(@DrawableRes id: Int, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Image(painterResource(id), contentDescription = null, modifier = modifier.size(size))
}
