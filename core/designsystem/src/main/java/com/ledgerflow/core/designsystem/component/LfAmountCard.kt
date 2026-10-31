package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextOverflow

/**
 * The amount as the head of a capture form (step F5, the Night Vault canvas):
 * one card holding what was paid, which book it goes to, the amount large in
 * Plex Mono in that book's colour, and where it came from.
 *
 * Review and Add entry both open on it, so the two screens that put money in
 * the books lead with the same thing (owner, 2026-10-29). The amount stays the
 * same editable [LfAmountField], aligned to the start here rather than centred.
 *
 * @param title what was paid (Review's merchant); none on a blank form.
 * @param tag the book, in the book's colour: "Expense" or "Income". Null while
 *   it is not known, rather than a guess (Law 2).
 * @param detail where the amount came from, in Plex Mono ("From an SMS · Ref …").
 */
@Composable
public fun LfAmountCard(
    value: String,
    onValueChange: (String) -> Unit,
    currencyCode: String,
    label: String,
    modifier: Modifier = Modifier,
    tone: LfAmountTone = LfAmountTone.Neutral,
    title: String? = null,
    tag: String? = null,
    detail: String? = null,
    focusRequester: FocusRequester? = null,
) {
    val tokens = LfAmountCardTokens
    LfCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
            CardHead(title, tag, tone)
            LfAmountField(
                value = value,
                onValueChange = onValueChange,
                currencyCode = currencyCode,
                label = label,
                tone = tone,
                focusRequester = focusRequester,
                horizontalAlignment = Alignment.Start,
            )
            detail?.let {
                Text(text = it, style = tokens.detailStyle, color = tokens.detail)
            }
        }
    }
}

/**
 * The payee and the book on one line, the tag moving under a long payee.
 * Either may be absent: a blank form has no payee, an unread message no book.
 */
@Composable
private fun CardHead(title: String?, tag: String?, tone: LfAmountTone) {
    val tokens = LfAmountCardTokens
    val titleText: (@Composable () -> Unit)? = title?.let {
        {
            Text(
                text = it,
                style = tokens.titleStyle,
                color = tokens.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    when {
        titleText != null && tag != null -> LfAdaptiveRow(
            modifier = Modifier.fillMaxWidth(),
            leading = titleText,
            trailing = { BookTag(tag, tone) },
        )
        titleText != null -> titleText()
        tag != null -> BookTag(tag, tone)
    }
}

/** The book, as a small outlined tag in its colour. Words say it, so colour is never the only cue (SPEC 9.6). */
@Composable
private fun BookTag(text: String, tone: LfAmountTone) {
    val tokens = LfAmountCardTokens
    val color = when (tone) {
        LfAmountTone.Debit -> tokens.debit
        LfAmountTone.Credit -> tokens.credit
        LfAmountTone.Neutral -> tokens.title
    }
    Text(
        text = text,
        style = tokens.tagStyle,
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .border(tokens.tagBorderWidth, tokens.tagBorder, tokens.tagShape)
            .padding(horizontal = tokens.tagInsetHorizontal, vertical = tokens.tagInsetVertical),
    )
}
