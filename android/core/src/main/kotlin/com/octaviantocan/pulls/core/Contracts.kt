package com.octaviantocan.pulls.core

/**
 * One `@cc` code contract from a `CONTRACTS` file: its [id], the values of each metadata key
 * (`owner`, `label`, ...; a `;` list is split), its Markdown [prose], and the [line] its
 * directive is on, counted from 1.
 */
data class Contract(
    val id: String,
    val metadata: Map<String, List<String>>,
    val prose: String,
    val line: Int,
) {
    val owners: List<String> get() = metadata["owner"].orEmpty()
    val labels: List<String> get() = metadata["label"].orEmpty()
}

/** A `CONTRACTS` file read by [parseContracts]: what parsed, and what did not, each problem with its line. */
data class ContractsFile(val path: String, val contracts: List<Contract>, val problems: List<String>) {
    /** The directory the file governs, repository-relative, "" for the root. */
    val directory: String get() = path.substringBeforeLast('/', "")
}

private val DIRECTIVE = Regex("""^@cc(?:\s+(.*))?$""")

/** `token`: non-empty, with no whitespace, commas, colons or square brackets. */
private val TOKEN = Regex("""^[^\s,:\[\]]+$""")

private val SECTION = Regex("""^== (.+) ==$""")

/**
 * Reads one `CONTRACTS` file by the `@cc` grammar of the code-contracts skill: a directive line
 * `@cc [key:value,...] id`, then prose up to the next directive or the end. Blank lines before the
 * first directive are allowed; anything else there is a problem. A malformed directive, empty
 * prose or an ID used twice is reported in [ContractsFile.problems] and never silently dropped:
 * a contract with a malformed directive is left out of [ContractsFile.contracts], a repeated ID
 * is kept so it can be seen.
 */
fun parseContracts(path: String, text: String): ContractsFile {
    val contracts = mutableListOf<Contract>()
    val problems = mutableListOf<String>()
    var head: Pair<Int, String>? = null
    val prose = mutableListOf<String>()
    val seen = mutableSetOf<String>()

    fun close() {
        val (line, rest) = head ?: return
        head = null
        val body = prose.joinToString("\n").trim('\n').trimEnd()
        prose.clear()
        val parsed = directive(rest)
        if (parsed == null) {
            problems += "$path:$line: malformed directive `@cc $rest`"
            return
        }
        val (id, metadata) = parsed
        if (body.isBlank()) problems += "$path:$line: contract $id has no prose"
        if (!seen.add(id)) problems += "$path:$line: contract ID $id is used twice in the file"
        contracts += Contract(id, metadata, body, line)
    }

    text.replace("\r\n", "\n").split('\n').forEachIndexed { index, raw ->
        val line = raw.trimEnd()
        val match = DIRECTIVE.matchEntire(line)
        when {
            match != null -> {
                close()
                head = index + 1 to (match.groupValues[1]).trim()
            }
            head != null -> prose += line
            line.isNotBlank() -> problems += "$path:${index + 1}: text before the first @cc directive"
        }
    }
    close()
    return ContractsFile(path, contracts, problems)
}

/** `[k:v,k:v] id` or `id`: the ID and the metadata, or null when it does not follow the grammar. */
private fun directive(rest: String): Pair<String, Map<String, List<String>>>? {
    if (rest.isEmpty()) return null
    if (!rest.startsWith("[")) return rest.takeIf { TOKEN.matches(it) }?.let { it to emptyMap() }
    val close = rest.indexOf(']')
    if (close < 0) return null
    val id = rest.substring(close + 1)
    if (id.isEmpty() || !id[0].isWhitespace()) return null
    val trimmed = id.trim()
    if (!TOKEN.matches(trimmed)) return null
    val metadata = linkedMapOf<String, MutableList<String>>()
    for (attribute in rest.substring(1, close).split(',')) {
        val colon = attribute.indexOf(':')
        if (colon < 0) return null
        val key = attribute.substring(0, colon)
        val value = attribute.substring(colon + 1)
        if (!TOKEN.matches(key) || !TOKEN.matches(value)) return null
        metadata.getOrPut(key) { mutableListOf() } += value.split(';').filter { it.isNotEmpty() }
    }
    return trimmed to metadata
}

/**
 * Splits the bundle the Android build writes (every `CONTRACTS` file of the repository, each after
 * a `== <repo-relative path> ==` line) and parses each file. Text before the first section is ignored.
 */
fun parseContractBundle(text: String): List<ContractsFile> {
    val files = mutableListOf<ContractsFile>()
    var path: String? = null
    val body = StringBuilder()
    fun close() {
        path?.let { files += parseContracts(it, body.toString()) }
        body.clear()
    }
    for (line in text.replace("\r\n", "\n").split('\n')) {
        val section = SECTION.matchEntire(line)
        if (section != null) {
            close()
            path = section.groupValues[1]
        }
        else if (path != null) {
            body.append(line).append('\n')
        }
    }
    close()
    return files
}

/**
 * What a set of `CONTRACTS` files breaks beyond their own syntax: a contract with no `owner` or no
 * `label`, a label outside [labels] when it is given, and an ID that a `CONTRACTS` file in a parent
 * directory already uses (IDs are unique within a file and across its parent files).
 */
fun contractProblems(files: List<ContractsFile>, labels: Set<String>? = null): List<String> {
    val problems = files.flatMap { it.problems }.toMutableList()
    for (file in files) {
        val ancestors = files.filter { it !== file && isAncestor(it.directory, file.directory) }
        for (contract in file.contracts) {
            val where = "${file.path}:${contract.line}"
            if (contract.owners.isEmpty()) problems += "$where: contract ${contract.id} has no owner"
            if (contract.labels.isEmpty()) problems += "$where: contract ${contract.id} has no label"
            labels?.let { known -> contract.labels.filter { it !in known }.forEach { problems += "$where: contract ${contract.id} has an unknown label $it" } }
            ancestors.filter { a -> a.contracts.any { it.id == contract.id } }.forEach { problems += "$where: contract ID ${contract.id} is already used in ${it.path}" }
        }
    }
    return problems
}

private fun isAncestor(parent: String, child: String): Boolean = parent != child && (parent.isEmpty() || child.startsWith("$parent/"))

private const val APP = "android/app/src/main/kotlin/com/octaviantocan/pulls"

/** Plain names for the directories that hold `CONTRACTS` files, in the order the UI Book lists them. */
private val AREAS = listOf(
    "" to "Whole project",
    "android" to "Whole app",
    "$APP/ui" to "Navigation and Commands",
    "$APP/ui/home" to "Home",
    "$APP/ui/review" to "Pull request pages",
    "$APP/ui/ai" to "Tours and AI",
    "$APP/ui/design" to "Kit",
    "$APP/ui/theme" to "Themes",
    "$APP/ui/settings" to "Settings",
    "$APP/ui/update" to "Updates",
    "$APP/update" to "Updates",
    "$APP/sound" to "Sound and touch",
    "$APP/data" to "Phone data",
    "$APP/ui/book" to "UI Book",
    "android/core" to "Shared logic",
    "packages/cli" to "Server",
    "packages/core" to "Review engine",
    "packages/app" to "Web app",
)

/**
 * The area of the code a `CONTRACTS` file governs, named in plain words ("Pull request pages",
 * "Server"), and its rank for ordering. A directory not named here is called by its last folder,
 * capitalised, and sorts after the named ones.
 */
fun contractArea(directory: String): Pair<String, Int> {
    val index = AREAS.indexOfFirst { it.first == directory }
    if (index >= 0) return AREAS[index].second to AREAS.indexOfFirst { it.second == AREAS[index].second }
    val name = directory.substringAfterLast('/').replace('-', ' ').replaceFirstChar { it.uppercase() }
    return name to AREAS.size
}
