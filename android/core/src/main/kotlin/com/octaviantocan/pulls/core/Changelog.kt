package com.octaviantocan.pulls.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * One version's section of `android/CHANGELOG.md`: its [summary] sentence and the [items] under it,
 * each a bullet's Markdown. [groups] are the same items under the section's own `### New`, `### Fixed`
 * headings, in order; a section without them is one group with no title. [pictures] are the section's
 * images and GIFs, each shown under the bullet it follows. [released] is when the version came out, from the
 * `#### 2026-10-26 14:30 UTC` line under its heading, or null for a section without one.
 */
data class ChangelogEntry(
    val version: String,
    val summary: String,
    val items: List<String>,
    val groups: List<ChangelogGroup> = if (items.isEmpty()) emptyList() else listOf(ChangelogGroup(null, items)),
    val pictures: List<ChangelogPicture> = emptyList(),
    val released: Instant? = null,
) {
    /** The pictures under the bullet at [item] in [items], or under the summary when [item] is -1. */
    fun picturesAfter(item: Int): List<ChangelogPicture> = pictures.filter { it.item == item }
}

/**
 * A `![alt](changelog/<version>/<name>.png)` line of a section: [path] is relative to `android/`, which the app
 * bundles as its assets and the server serves under `/android/`. [item] is the index in [ChangelogEntry.items] of
 * the bullet it follows, or -1 when it comes before any. Tavi asked on 16 Oct 10:49 for every changelog to show
 * what changed in pictures, and GIFs for what moves.
 */
data class ChangelogPicture(val path: String, val alt: String, val item: Int)

/** Bullets under a `### <title>` heading inside a version's section, or before any such heading when [title] is null. */
data class ChangelogGroup(val title: String?, val items: List<String>)

private val HEADING = Regex("""^##\s+(\S+)\s*$""")
private val GROUP = Regex("""^###\s+(.+?)\s*$""")
private val BULLET = Regex("""^\s*[-*]\s+(.*)$""")
private val COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
private val RELEASED = Regex("""^####\s+(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}) UTC\s*$""")
private val PICTURE = Regex("""^\s*!\[([^\]]*)]\(([^)\s]+)\)\s*$""")

/**
 * The sections of a changelog, in file order (newest first). A section is a `## <version>` heading, a
 * first paragraph that is its summary, and bullets, which `### <title>` headings may sort into groups.
 * Comments and the `#` title are skipped. Older builds skip the `###` lines too and read the same bullets
 * as one list, so a section with groups still shows on a phone that has not updated yet. A picture line,
 * written unindented on its own line under a bullet, is skipped by builds from before 0.40.0 the same way
 * (changelog.mjs checks it sits there: indented it would join the bullet, above the bullets the summary).
 * The `#### <date> <time> UTC` line under a heading is when that version was released (Tavi, 26 Oct 2026); builds
 * from before it skip the line like any other `#` line, and a line that is not a real time leaves [ChangelogEntry.released] null.
 */
fun parseChangelog(text: String): List<ChangelogEntry> {
    val entries = mutableListOf<ChangelogEntry>()
    var version: String? = null
    val summary = StringBuilder()
    val items = mutableListOf<String>()
    val pictures = mutableListOf<ChangelogPicture>()
    var released: Instant? = null
    // Where each group starts in [items], and its title.
    val starts = mutableListOf<Pair<String?, Int>>()
    fun close() {
        val v = version ?: return
        val bounds = if (starts.firstOrNull()?.second == 0) starts.toList() else listOf<Pair<String?, Int>>(null to 0) + starts
        val groups = bounds.mapIndexed { i, (title, from) -> ChangelogGroup(title, items.subList(from, bounds.getOrNull(i + 1)?.second ?: items.size).toList()) }
            .filter { it.items.isNotEmpty() }
        entries += ChangelogEntry(v, summary.toString().trim(), items.toList(), groups, pictures.toList(), released)
        released = null
        summary.clear()
        items.clear()
        pictures.clear()
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
        val stamp = RELEASED.matchEntire(line)
        if (version != null && stamp != null && released == null) {
            released = runCatching { LocalDateTime.parse("${stamp.groupValues[1]}T${stamp.groupValues[2]}").toInstant(ZoneOffset.UTC) }.getOrNull()
            continue
        }
        if (version == null || line.isBlank() || line.startsWith("#")) continue
        val picture = PICTURE.matchEntire(line)
        if (picture != null) {
            pictures += ChangelogPicture(picture.groupValues[2], picture.groupValues[1], items.lastIndex)
            continue
        }
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

/** When a version came out, as the reader's own calendar and clock write it: "26 Oct 2026, 14:28" in Britain. */
fun changelogTime(released: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone).format(released)
