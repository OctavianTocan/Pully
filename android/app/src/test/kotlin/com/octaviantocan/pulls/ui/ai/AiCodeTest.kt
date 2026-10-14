package com.octaviantocan.pulls.ui.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Code and flows in what the AI writes (rule 95). */
class AiCodeTest {
    @Test
    fun fenceLanguagesMapToTheExtensionsTheHighlighterKnows() {
        assertEquals("ts", fenceExtension("TypeScript"))
        assertEquals("kt", fenceExtension("kotlin"))
        assertEquals("sh", fenceExtension("bash"))
        assertNull(fenceExtension(""))
        assertNull(fenceExtension("flow"))
    }

    @Test
    fun aFlowReadsOneHopPerLineWithItsLabel() {
        assertEquals(
            listOf(FlowHop("App", "Server", "the request"), FlowHop("Server", "Cache", "")),
            parseFlow("App -> Server: the request\nServer -> Cache"),
        )
    }

    @Test
    fun aChainOnOneLineLabelsOnlyItsLastHop() {
        assertEquals(
            listOf(FlowHop("Page", "Store", ""), FlowHop("Store", "API", "a patch")),
            parseFlow("Page → Store => API: a patch"),
        )
    }

    @Test
    fun aLabelWrittenOnTheArrowIsKept() {
        assertEquals(listOf(FlowHop("Mount", "Gate", "the token")), parseFlow("- Mount --the token--> Gate"))
    }

    @Test
    fun eachArrowOfAChainKeepsItsOwnLabel() {
        assertEquals(
            listOf(FlowHop("A", "B", "request"), FlowHop("B", "C", "response")),
            parseFlow("A --request--> B --response--> C"),
        )
    }

    @Test
    fun aSketchWithALineThatIsNotAHopStaysText() {
        assertNull(parseFlow("App -> Server\nthen it waits"))
        assertNull(parseFlow(""))
    }

    @Test
    fun runsBreakWhereAHopDoesNotStartWhereTheLastEnded() {
        val hops = listOf(FlowHop("A", "B", ""), FlowHop("B", "C", ""), FlowHop("X", "B", ""))
        assertEquals(listOf(hops.take(2), hops.drop(2)), flowRuns(hops))
    }

    @Test
    fun aPlainAnswerDropsMarkdownButKeepsTheWords() {
        assertEquals(
            "The gate in OjinAgentMount.tsx waits.\nconst a = 1",
            plainAnswer("The **gate** in [`OjinAgentMount.tsx`](src/OjinAgentMount.tsx#L12) waits.\n```ts\nconst a = 1\n```"),
        )
    }
}
