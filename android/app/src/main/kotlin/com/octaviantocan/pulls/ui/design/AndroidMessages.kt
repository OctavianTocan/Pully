package com.octaviantocan.pulls.ui.design

import android.content.Context
/** Compatibility entry point for Android services and the current theme. */
fun Messages.Companion.load(context: Context, locale: String): Messages =
    loadAndroidMessages(context, locale)
