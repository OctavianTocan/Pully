package com.octaviantocan.pulls.ui.ai

import com.octaviantocan.pulls.data.Tour

/** A particular generated tour read against a particular newer commit, independent of refreshed object instances. */
data class StaleTourIdentity(val originalHead: String, val generatedAt: String, val currentHead: String)

/** Missing commit metadata cannot establish that a tour is stale. */
fun staleTourIdentity(tour: Tour?, currentHead: String?): StaleTourIdentity? {
    val original = tour?.headSha?.takeIf { it.isNotBlank() } ?: return null
    val current = currentHead?.takeIf { it.isNotBlank() && it != original } ?: return null
    return StaleTourIdentity(original, tour.generatedAt, current)
}

