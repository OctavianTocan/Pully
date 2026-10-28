package com.octaviantocan.pulls.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContractsTest {
    @Test
    fun aDirectiveAndItsProseMakeAContract() {
        val file = parseContracts(
            "CONTRACTS",
            """
            @cc [owner:OctavianTocan,label:product;security] back-keeps-place
            Back MUST return to the same place.
            It spans lines.

            @cc [owner:spolu] plain
            One line.
            """.trimIndent(),
        )
        assertEquals(emptyList(), file.problems)
        assertEquals(listOf("back-keeps-place", "plain"), file.contracts.map { it.id })
        val first = file.contracts[0]
        assertEquals(listOf("OctavianTocan"), first.owners)
        assertEquals(listOf("product", "security"), first.labels)
        assertEquals("Back MUST return to the same place.\nIt spans lines.", first.prose)
        assertEquals(1, first.line)
        assertEquals(5, file.contracts[1].line)
    }

    @Test
    fun repeatedKeysAddUp() {
        val contract = parseContracts("CONTRACTS", "@cc [owner:a,owner:b;c,label:design] x\nProse.").contracts.single()
        assertEquals(listOf("a", "b", "c"), contract.owners)
    }

    @Test
    fun aDirectiveWithoutMetadataIsJustAnId() {
        val contract = parseContracts("CONTRACTS", "@cc bare-id\nProse.").contracts.single()
        assertEquals("bare-id", contract.id)
        assertTrue(contract.metadata.isEmpty())
    }

    @Test
    fun malformedDirectivesAreReportedNotDropped() {
        val file = parseContracts(
            "a/CONTRACTS",
            """
            @cc [owner:x,label] missing-value
            Prose.
            @cc [owner:x] two words
            Prose.
            @cc [owner:x]no-space
            Prose.
            @cc
            Prose.
            """.trimIndent(),
        )
        assertTrue(file.contracts.isEmpty())
        assertEquals(4, file.problems.size, file.problems.joinToString("\n"))
        assertTrue(file.problems.all { it.startsWith("a/CONTRACTS:") && "malformed" in it })
    }

    @Test
    fun emptyProseTextFirstAndDuplicatesAreProblems() {
        val file = parseContracts("CONTRACTS", "stray\n@cc [owner:x,label:y] a\n\n@cc [owner:x,label:y] a\nProse.")
        assertEquals(3, file.problems.size, file.problems.joinToString("\n"))
        assertTrue(file.problems[0].contains(":1: text before"))
        assertTrue(file.problems.any { "no prose" in it })
        assertTrue(file.problems.any { "used twice" in it })
    }

    @Test
    fun aBundleSplitsIntoFilesByPath() {
        val files = parseContractBundle(
            """
            == CONTRACTS ==
            @cc [owner:x,label:architecture] root-rule
            Root.
            == android/CONTRACTS ==
            @cc [owner:x,label:design] app-rule
            App.
            """.trimIndent(),
        )
        assertEquals(listOf("CONTRACTS", "android/CONTRACTS"), files.map { it.path })
        assertEquals(listOf("", "android"), files.map { it.directory })
        assertEquals("Root.", files[0].contracts.single().prose)
        assertEquals(listOf("root-rule", "app-rule"), files.flatMap { it.contracts }.map { it.id })
    }

    @Test
    fun anIdUsedInAParentFileIsAProblem() {
        val files = listOf(
            parseContracts("CONTRACTS", "@cc [owner:x,label:design] same\nRoot."),
            parseContracts("android/CONTRACTS", "@cc [owner:x,label:design] same\nApp."),
            parseContracts("packages/CONTRACTS", "@cc [owner:x,label:design] other\nNot a child of android."),
            parseContracts("android2/CONTRACTS", "@cc [owner:x,label:design] other\nA sibling, not a child."),
        )
        val problems = contractProblems(files)
        assertEquals(1, problems.size, problems.joinToString("\n"))
        assertTrue(problems.single().startsWith("android/CONTRACTS:1: contract ID same is already used in CONTRACTS"))
    }

    @Test
    fun ownerAndLabelAreRequiredAndLabelsKnown() {
        val files = listOf(parseContracts("CONTRACTS", "@cc [label:design] a\nX.\n@cc [owner:x] b\nY.\n@cc [owner:x,label:vibes] c\nZ."))
        val problems = contractProblems(files, setOf("design"))
        assertEquals(3, problems.size, problems.joinToString("\n"))
        assertTrue(problems[0].contains("a has no owner"))
        assertTrue(problems[1].contains("b has no label"))
        assertTrue(problems[2].contains("unknown label vibes"))
    }

    @Test
    fun areasHavePlainNames() {
        assertEquals("Whole project", contractArea("").first)
        assertEquals("Pull request pages", contractArea("android/app/src/main/kotlin/com/octaviantocan/pulls/ui/review").first)
        assertEquals("Server", contractArea("backend").first)
        assertEquals("Server launcher", contractArea("packages/cli").first)
        assertEquals(contractArea("ai-kit"), contractArea("ai-kit/kotlin/src/commonMain/kotlin/com/octaviantocan/aikit"))
        assertTrue(contractArea("backend").second < contractArea("packages/cli").second)
        // Two directories may share an area, and rank with it.
        assertEquals(contractArea("android/app/src/main/kotlin/com/octaviantocan/pulls/ui/update"), contractArea("android/app/src/main/kotlin/com/octaviantocan/pulls/update"))
        assertEquals("Odd folder", contractArea("tools/odd-folder").first)
        assertTrue(contractArea("tools/odd-folder").second > contractArea("packages/app").second)
    }
}
