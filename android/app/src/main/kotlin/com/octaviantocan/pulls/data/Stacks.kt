package com.octaviantocan.pulls.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * `pr-stack`: the stack a pull request is a layer of (`PullRequestStack` in core
 * `providers/github/stacks.ts`). GitHub's own stack when it has one, else one inferred from
 * branch names, a pull request sitting on the one whose head branch is its base.
 */

/** One layer. [position] 1 sits on the base branch; siblings of a fork share a position. */
data class StackEntry(
    val number: Int,
    val title: String,
    /** `open`, `draft`, `merged` or `closed`. */
    val state: String,
    val position: Int,
    /** The pull request the stack was asked for. */
    val current: Boolean,
    val headRef: String,
    val baseRef: String,
    /** `success`, `failure`, `pending`, `none`; empty when unknown. */
    val checks: String = "",
    val additions: Int? = null,
    val deletions: Int? = null,
)

/** A stack, [entries] bottom first; [base] is the trunk under the bottom layer. */
data class PrStack(
    val native: Boolean,
    /** GitHub's stack number; native stacks only. */
    val number: Int?,
    val base: String,
    val entries: List<StackEntry>,
) {
    val current: StackEntry? get() = entries.firstOrNull { it.current }

    /** The layer the current pull request sits on: the one whose head is its base, or else the one a position lower. */
    val below: StackEntry?
        get() {
            val here = current ?: return null
            if (here.position <= 1) return null
            return entries.firstOrNull { !it.current && it.headRef.isNotEmpty() && it.headRef == here.baseRef }
                ?: entries.firstOrNull { !it.current && it.position == here.position - 1 }
        }
}

/** The `stack` of a `pr-stack` answer; null when the pull request is in none or the stack holds only it. */
fun JsonElement.asPrStack(): PrStack? {
    val obj = this as? JsonObject ?: return null
    val layers = obj.arr("entries").map { layer ->
        StackEntry(
            number = layer.i("number") ?: 0,
            title = layer.s("title"),
            state = layer.s("state").ifBlank { "open" },
            position = layer.i("position") ?: 0,
            current = layer.b("current"),
            headRef = layer.s("headRef"),
            baseRef = layer.s("baseRef"),
            checks = layer.s("checks"),
            additions = layer.i("additions"),
            deletions = layer.i("deletions"),
        )
    }.sortedWith(compareBy({ it.position }, { it.number }))
    if (layers.size < 2) return null
    return PrStack(native = obj.b("native"), number = obj.i("number"), base = obj.s("base"), entries = layers)
}
