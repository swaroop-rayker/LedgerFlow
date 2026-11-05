package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.ledgerflow.core.designsystem.format.ProseBlock
import com.ledgerflow.core.designsystem.format.ProseSpan

/**
 * Text the app ships for reading: What's new, Help, the privacy policy and the
 * terms (Settings > About, V2-3; DESIGN_SYSTEM_PLAN §5.6). Blocks come from
 * [com.ledgerflow.core.designsystem.format.ProseMarkdown].
 *
 * Headings are headings to TalkBack, so a long policy can be read section by
 * section. A list item's bullet is decoration: the item is read as its words.
 */
@Composable
public fun LfProse(blocks: List<ProseBlock>, modifier: Modifier = Modifier, tone: LfProseTone = LfProseTone.Primary) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(LfProseTokens.bulletGap)) {
        blocks.forEachIndexed { index, block -> LfProseBlock(block = block, first = index == 0, tone = tone) }
    }
}

/**
 * [Primary] for a page that is the text (a policy); [Secondary] for text under
 * something it answers (an FAQ answer under its question), so the two read as
 * two things rather than one long question.
 */
public enum class LfProseTone { Primary, Secondary }

/**
 * The same blocks as lazy items, for a page whose text runs past the screen:
 * a privacy policy composes only the paragraphs on screen.
 */
public fun LazyListScope.lfProseItems(blocks: List<ProseBlock>, keyPrefix: String) {
    items(
        count = blocks.size,
        key = { "$keyPrefix-$it" },
        contentType = { blocks[it]::class.simpleName },
    ) { index -> LfProseBlock(block = blocks[index], first = index == 0) }
}

/** One block. [first] drops a heading's top gap, which only separates it from text above. */
@Composable
public fun LfProseBlock(
    block: ProseBlock,
    modifier: Modifier = Modifier,
    first: Boolean = false,
    tone: LfProseTone = LfProseTone.Primary,
) {
    val tokens = LfProseTokens
    val textColor = when (tone) {
        LfProseTone.Primary -> tokens.text
        LfProseTone.Secondary -> tokens.secondaryText
    }
    when (block) {
        is ProseBlock.Heading -> Text(
            text = block.spans.styled(),
            style = tokens.headingStyle,
            color = tokens.text,
            modifier = modifier
                .fillMaxWidth()
                .then(if (first) Modifier else Modifier.padding(top = tokens.headingTopGap))
                .semantics { heading() },
        )
        is ProseBlock.Paragraph -> Text(
            text = block.spans.styled(),
            style = tokens.bodyStyle,
            color = textColor,
            modifier = modifier.fillMaxWidth(),
        )
        is ProseBlock.Bullet -> Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(tokens.bulletGap),
        ) {
            Text(
                text = BULLET,
                style = tokens.bodyStyle,
                color = tokens.bullet,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Text(
                text = block.spans.styled(),
                style = tokens.bodyStyle,
                color = textColor,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun List<ProseSpan>.styled(): AnnotatedString {
    val code = LfProseTokens.codeStyle
    val emphasis = LfProseTokens.emphasisWeight
    return buildAnnotatedString {
        forEach { span ->
            when {
                span.bold -> withStyle(SpanStyle(fontWeight = emphasis)) { append(span.text) }
                span.code -> withStyle(SpanStyle(fontFamily = code.fontFamily)) { append(span.text) }
                else -> append(span.text)
            }
        }
    }
}

/** A bullet (U+2022), as a code point (CLAUDE.md §5). */
private val BULLET: String = Char(0x2022).toString()
