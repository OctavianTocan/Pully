package com.octaviantocan.pulls.ui.ai

/** A particular generated tour read against a particular newer commit, independent of refreshed object instances. */
data class StaleTourIdentity(val originalHead: String, val generatedAt: String, val currentHead: String)
