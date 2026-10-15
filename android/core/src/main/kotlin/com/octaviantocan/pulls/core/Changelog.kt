package com.octaviantocan.pulls.core

/**
 * One version's section of `android/CHANGELOG.md`: its [summary] sentence and the [items] under it,
 * each a bullet's Markdown. [groups] are the same items under the section's own `### New`, `### Fixed`
 * headings, in order; a section without them is one group with no title.
 */
data class ChangelogEntry(
    val version: String,
    val summary: String,
    val items: List<String>,
    val groups: List<ChangelogGroup> = if (items.isEmpty()) emptyList() else listOf(ChangelogGroup(null, items)),
)

/** Bullets under a `### <title>` heading inside a version's section, or before any such heading when [title] is null. */
data class ChangelogGroup(val title: String?, val items: List<String>)

private val HEADING = Regex("""^##\s+(\S+)\s*$""")
private val GROUP = Regex("""^###\s+(.+?)\s*$""")
private val BULLET = Regex("""^\s*[-*]\s+(.*)$""")
private val COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

/**
 * The sections of a changelog, in file order (newest first). A section is a `## <version>` heading, a
 * first paragraph that is its summary, and bullets, which `### <title>` headings may sort into groups.
 * Comments and the `#` title are skipped. Older builds skip the `###` lines too and read the same bullets
 * as one list, so a section with groups still shows on a phone that has not updated yet.
 */
fun parseChangelog(text: String): List<ChangelogEntry> {
    val entries = mutableListOf<ChangelogEntry>()
    var version: String? = null
    val summary = StringBuilder()
    val items = mutableListOf<String>()
    // Where each group starts in [items], and its title.
    val starts = mutableListOf<Pair<String?, Int>>()
    fun close() {
        val v = version ?: return
        val bounds = if (starts.firstOrNull()?.second == 0) starts.toList() else listOf<Pair<String?, Int>>(null to 0) + starts
        val groups = bounds.mapIndexed { i, (title, from) -> ChangelogGroup(title, items.subList(from, bounds.getOrNull(i + 1)?.second ?: items.size).toList()) }
            .filter { it.items.isNotEmpty() }
        entries += ChangelogEntry(v, summary.toString().trim(), items.toList(), groups)
        summary.clear()
        items.clear()
        starts.clear()
    }
    for (line in COMMENT.replace(text, "").lines()) {
        val heading = HEADING.matchEntire(line)
        if (heading != null) {
            close()
            version = heading.groupValues[1]
            continue
        }
        val group = GROUP.matchEntire(line)
        if (version != null && group != null) {
            starts += group.groupValues[1] to items.size
            continue
        }
        if (version == null || line.isBlank() || line.startsWith("#")) continue
        val bullet = BULLET.matchEntire(line)
        when {
            bullet != null -> items += bullet.groupValues[1].trim()
            // A wrapped bullet carries on the line under it, indented.
            items.isNotEmpty() && line.first().isWhitespace() -> items[items.lastIndex] = "${items.last()} ${line.trim()}"
            items.isEmpty() -> summary.append(if (summary.isEmpty()) "" else " ").append(line.trim())
        }
    }
    close()
    return entries
}

/**
 * Compares two version names part by part as numbers (`0.10.0` is above `0.9.2`). A part that is not a
 * number counts as 0, and a missing part as 0, so `1.2` equals `1.2.0`.
 */
fun compareVersions(a: String, b: String): Int {
    val left = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val right = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(left.size, right.size)) {
        val diff = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
        if (diff != 0) return diff
    }
    return 0
}

/**
 * The entries a phone that ran [after] and now runs [upTo] has not seen, newest first: every version
 * above [after] and at most [upTo]. With no [after] (nothing recorded yet), only [upTo]'s own entry.
 */
fun List<ChangelogEntry>.since(after: String?, upTo: String): List<ChangelogEntry> =
    filter { compareVersions(it.version, upTo) <= 0 && (if (after == null) compareVersions(it.version, upTo) == 0 else compareVersions(it.version, after) > 0) }
        .sortedWith { x, y -> compareVersions(y.version, x.version) }

/** Markdown emphasis, code ticks and link targets taken out, for a notification that shows plain text. */
fun plainChangelogText(markdown: String): String =
    markdown
        .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
        .replace(Regex("""[*_`]+"""), "")
        .trim()
