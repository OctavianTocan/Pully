package com.octaviantocan.pulls.core.markdown

import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.sequentialparsers.DelimiterParser
import org.intellij.markdown.parser.sequentialparsers.EmphasisLikeParser
import org.intellij.markdown.parser.sequentialparsers.SequentialParser
import org.intellij.markdown.parser.sequentialparsers.SequentialParserManager
import org.intellij.markdown.parser.sequentialparsers.TokensCache
import org.intellij.markdown.parser.sequentialparsers.impl.AutolinkParser
import org.intellij.markdown.parser.sequentialparsers.impl.BacktickParser
import org.intellij.markdown.parser.sequentialparsers.impl.EmphStrongDelimiterParser
import org.intellij.markdown.parser.sequentialparsers.impl.ImageParser
import org.intellij.markdown.parser.sequentialparsers.impl.InlineLinkParser
import org.intellij.markdown.parser.sequentialparsers.impl.ReferenceLinkParser

/** Existing GFM features only: Android has no math extension and permits both single and double strike. */
internal class PullyGfmFlavour : GFMFlavourDescriptor() {
    override val sequentialParserManager = object : SequentialParserManager() {
        override fun getParserSequence(): List<SequentialParser> = listOf(
            AutolinkParser(listOf(MarkdownTokenTypes.AUTOLINK, MarkdownTokenTypes.EMAIL_AUTOLINK, GFMTokenTypes.GFM_AUTOLINK)),
            BacktickParser(), ImageParser(), InlineLinkParser(), ReferenceLinkParser(),
            EmphasisLikeParser(EmphStrongDelimiterParser(), ExistingStrikeParser()),
        )
    }
}

/** JetBrains' delimiter parser, adapting only its strike node range to CommonMark's existing single-tilde support. */
private class ExistingStrikeParser : DelimiterParser() {
    override fun scan(tokens: TokensCache, iterator: TokensCache.Iterator, delimiters: MutableList<Info>): Int {
        if (iterator.type != GFMTokenTypes.TILDE) return 0
        var steps = 1
        var right = iterator
        for (index in 0 until maxAdvance) {
            if (right.rawLookup(1) != GFMTokenTypes.TILDE) break
            right = right.advance()
            steps++
        }
        val (open, close) = canOpenClose(tokens, iterator, right, canSplitText = true)
        for (index in 0 until steps) delimiters += Info(
            tokenType = GFMTokenTypes.TILDE, position = iterator.index + index, length = 0,
            canOpen = open, canClose = close, marker = '~',
        )
        return steps
    }

    override fun process(tokens: TokensCache, iterator: TokensCache.Iterator, delimiters: MutableList<Info>, result: SequentialParser.ParsingResultBuilder) {
        var skipNext = false
        for (index in delimiters.indices.reversed()) {
            if (skipNext) { skipNext = false; continue }
            val opener = delimiters[index]
            if (opener.tokenType != GFMTokenTypes.TILDE || opener.closerIndex == -1) continue
            skipNext = EmphStrongDelimiterParser.areAdjacentSameMarkers(delimiters, index, opener.closerIndex)
            val closer = delimiters[opener.closerIndex]
            val range = if (skipNext) opener.position - 1..closer.position + 2 else opener.position..closer.position + 1
            result.withNode(SequentialParser.Node(range, GFMElementTypes.STRIKETHROUGH))
        }
    }
}
