package com.ledgerflow.core.ui.phrase

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.sensitiveContent

/**
 * Marks a node that shows or takes the recovery phrase (owner, 2026-09-27).
 *
 * **Accessibility, without handing the phrase to every service.** TalkBack
 * needs the words: a chip labelled "Word 3, …" is how a blind user checks what
 * they typed. But those labels are readable by *any* accessibility service —
 * which is how a screen dump could read the phrase (SESSION-LOG-S15). With
 * [isSensitiveData], Compose answers a service that is not a declared
 * accessibility tool with an **empty node and no children**, and marks this
 * node's events sensitive. TalkBack is a tool and still reads everything; other
 * services and dump tools get nothing. Android 14+; a no-op below.
 *
 * **Not in screen sharing or recording.** [sensitiveContent] makes Android 15+
 * blank the window while such a node is on screen — the phrase, and the camera
 * pointed at a Recovery Kit's QR code, which *is* the phrase.
 *
 * Put it on every node that carries a word — a container hides its subtree from
 * tree-walking readers, but events are judged per node, so a text field or a
 * chip needs it on itself too. `PhraseSecretTest` holds each site.
 */
public fun Modifier.phraseSecret(): Modifier = this
    .sensitiveContent()
    .semantics { isSensitiveData = true }
