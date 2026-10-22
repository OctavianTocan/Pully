package com.octaviantocan.pulls.ui.home

/** Shared unit selection with each platform's localized, named relative-date wording. */
expect fun timeAgo(thenMs: Long, nowMs: Long, locale: String): String
