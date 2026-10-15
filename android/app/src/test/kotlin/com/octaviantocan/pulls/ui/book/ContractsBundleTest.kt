package com.octaviantocan.pulls.ui.book

import com.octaviantocan.pulls.core.contractProblems
import com.octaviantocan.pulls.core.parseContractBundle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reads `contracts.txt`, the bundle of every `CONTRACTS` file that the build puts in the APK for the UI
 * Book (the Gradle `contractsAsset` task; its path comes in as `pulls.contracts`), the way the app does.
 */
class ContractsBundleTest {
    private val bundle = File(checkNotNull(System.getProperty("pulls.contracts")) { "the build passes the bundle as -Dpulls.contracts" })
    private val files = parseContractBundle(bundle.readText())

    @Test
    fun theBundleHoldsTheRepositoryContracts() {
        val paths = files.map { it.path }
        assertTrue("CONTRACTS" in paths, "no root CONTRACTS in $paths")
        assertTrue("android/CONTRACTS" in paths, "no android/CONTRACTS in $paths")
        assertTrue(paths.none { "node_modules" in it || "/build/" in it }, "bundled a generated copy: $paths")
        assertEquals(paths.sorted(), paths)
        assertTrue(files.all { it.contracts.isNotEmpty() }, "an empty CONTRACTS file")
    }

    @Test
    fun everyContractParsesWithOwnerLabelAndAUniqueId() {
        assertEquals(emptyList(), contractProblems(files, CONTRACT_LABELS.toSet()))
        files.forEach { file ->
            val ids = file.contracts.map { it.id }
            assertEquals(ids.distinct(), ids, "duplicate IDs in ${file.path}")
            file.contracts.forEach { contract ->
                assertTrue(contract.owners.isNotEmpty(), "${file.path}: ${contract.id} has no owner")
                assertTrue(contract.labels.isNotEmpty(), "${file.path}: ${contract.id} has no label")
                assertTrue(contract.prose.isNotBlank(), "${file.path}: ${contract.id} has no prose")
            }
        }
    }

    @Test
    fun everyAreaHasAPlainName() {
        val areas = contractAreas(files)
        assertEquals(files.sumOf { it.contracts.size }, areas.sumOf { it.contracts.size })
        assertTrue(areas.none { it.name.isBlank() || "/" in it.name })
        assertEquals("Whole project", areas.first().name)
    }

    @Test
    fun theFilterAndSearchNarrow() {
        val contract = files.first().contracts.first()
        assertTrue(contractShown(contract, "Whole project", null, ""))
        assertTrue(contractShown(contract, "Whole project", contract.labels.first(), contract.id))
        assertTrue(!contractShown(contract, "Whole project", null, "zzz-no-such-word"))
    }
}
