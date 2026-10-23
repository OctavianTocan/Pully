package com.octaviantocan.pulls.ui.settings

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
actual fun dayOf(iso: String): String = runCatching { DAY.format(Instant.parse(iso).atZone(ZoneId.systemDefault())) }.getOrDefault(iso)
