package com.octaviantocan.pulls.ui.design

/** Does [text] match what was typed in a a sheet search? Every word of [query] is somewhere in it, in any order and case (rule 101). */
fun matchesSearch(text: String, query: String): Boolean =
    query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.all { text.lowercase().contains(it) }
