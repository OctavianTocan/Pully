package com.octaviantocan.pulls.ui.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Code and flows in what the AI writes (rule 95). The call trees, file trees and pseudocode are tested in core (`SketchTest`). */
class AiCodeTest {
    @Test
    fun fenceLanguagesMapToTheExtensionsTheHighlighterKnows() {
        assertEquals("ts", fenceExtension("TypeScript"))
        assertEquals("kt", fenceExtension("kotlin"))
        assertEquals("sh", fenceExtension("bash"))
        assertNull(fenceExtension(""))
        assertNull(fenceExtension("flow"))
        assertNull(fenceExtension("pseudo"))
    }

    private fun hops(text: String) = parseFlow(text)?.let(::flowHops)

    @Test
    fun aFlowReadsOneHopPerLineWithItsLabel() {
        assertEquals(
            listOf(FlowHop("App", "Server", "the request"), FlowHop("Server", "Cache", "")),
            hops("App -> Server: the request\nServer -> Cache"),
        )
    }

    @Test
    fun aChainOnOneLineLabelsOnlyItsLastHop() {
        assertEquals(
            listOf(FlowHop("Page", "Store", ""), FlowHop("Store", "API", "a patch")),
            hops("Page → Store => API: a patch"),
        )
    }

    @Test
    fun aLabelWrittenOnTheArrowIsKept() {
        assertEquals(listOf(FlowHop("Mount", "Gate", "the token")), hops("- Mount --the token--> Gate"))
    }

    @Test
    fun eachArrowOfAChainKeepsItsOwnLabel() {
        assertEquals(
            listOf(FlowHop("A", "B", "request"), FlowHop("B", "C", "response")),
            hops("A --request--> B --response--> C"),
        )
    }

    @Test
    fun aChainWrittenDownThePageIsOneRun() {
        // The flow of the 14 Oct recording (F59): a start, then each step on its own line after an arrow.
        val flow = "loadOjinAgent()\n  → append <ojin-agent>\n  → onElement(el): bind page listeners\n  → next tick: el.connect({ interactive: true })"
        assertEquals(
            listOf(
                FlowItem.Run(
                    listOf(
                        FlowHop("loadOjinAgent()", "append <ojin-agent>", ""),
                        FlowHop("append <ojin-agent>", "onElement(el)", "bind page listeners"),
                        FlowHop("onElement(el)", "next tick", "el.connect({ interactive: true })"),
                    ),
                ),
            ),
            parseFlow(flow),
        )
    }

    @Test
    fun aColonInsideBracketsIsCodeNotALabel() {
        assertEquals(listOf(FlowHop("Page", "el.connect({ a: 1 })", "")), hops("Page -> el.connect({ a: 1 })"))
    }

    @Test
    fun aLineThatIsNotAHopStaysAsWordsAndTheRestIsDrawn() {
        assertEquals(
            listOf(FlowItem.Run(listOf(FlowHop("App", "Server", ""))), FlowItem.Note("then it waits"), FlowItem.Run(listOf(FlowHop("Server", "Cache", "")))),
            parseFlow("App -> Server\nthen it waits\nServer -> Cache"),
        )
        // An arrow with nothing before it to lead on from is words too.
        assertEquals(listOf(FlowItem.Note("→ orphan"), FlowItem.Run(listOf(FlowHop("A", "B", "")))), parseFlow("→ orphan\nA -> B"))
    }

    @Test
    fun aSketchWithNoHopAtAllStaysText() {
        assertNull(parseFlow("first it loads\nthen it waits"))
        assertNull(parseFlow(""))
    }

    @Test
    fun hopsThatMeetJoinOneRunAndOneThatDoesNotStartsAnother() {
        assertEquals(
            listOf(FlowItem.Run(listOf(FlowHop("A", "B", ""), FlowHop("B", "C", ""))), FlowItem.Run(listOf(FlowHop("X", "B", "")))),
            parseFlow("A -> B\nB -> C\nX -> B"),
        )
    }

    @Test
    fun aPlainAnswerDropsMarkdownButKeepsTheWords() {
        assertEquals(
            "The gate in OjinAgentMount.tsx waits.\nconst a = 1",
            plainAnswer("The **gate** in [`OjinAgentMount.tsx`](src/OjinAgentMount.tsx#L12) waits.\n```ts\nconst a = 1\n```"),
        )
    }

    @Test
    fun aCallChainSplitsIntoItsStepsAtSpacedArrows() {
        // N16: each step of `AskPill → openAsk → AskSheet` stands on a line of its own; `a->b` in code stays one name.
        assertEquals(listOf("AskPill", "openAsk", "Ask Sheet"), callChain("AskPill → openAsk → Ask Sheet"))
        assertEquals(listOf("onReached", "recordReached"), callChain("onReached -> recordReached"))
        assertEquals(listOf("p->next"), callChain("p->next"))
        assertEquals(listOf("pages.last() == Tour"), callChain("pages.last() == Tour"))
    }

    @Test
    fun aCaptionIsItsFirstSentence() {
        // N33: "One runtime step, in the order it runs. The second drain has no delay check." stood in two lines.
        assertEquals("One runtime step, in the order it runs.", firstSentence("One runtime step, in the order it runs. The second drain has no delay check."))
        assertEquals("Write path after this change", firstSentence(" Write path after this change "))
        assertEquals("", firstSentence(""))
    }
}
