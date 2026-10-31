package com.ledgerflow.feature.settings.about

import android.content.Context
import androidx.compose.runtime.Immutable
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.designsystem.format.ProseBlock
import com.ledgerflow.core.designsystem.format.ProseMarkdown
import com.ledgerflow.core.designsystem.format.plainText
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The texts Settings > About shows (V2-PLAN §3.8), bundled in the APK under
 * `assets/about/`. All offline: nothing is fetched, and Help works on a plane.
 *
 * The privacy policy is `docs/play/privacy-policy.md` itself, copied in by the
 * build (`bundleAboutDocuments`); the others live in this module's assets.
 */
public enum class AboutDocument(public val title: String, internal val asset: String) {
    WhatsNew("What's new", "whats-new.md"),
    PrivacyPolicy("Privacy policy", "privacy-policy.md"),
    Terms("Terms", "terms.md"),
}

/** The page a route's argument names, or null for a name no document has. */
public fun aboutDocumentNamed(name: String): AboutDocument? = AboutDocument.entries.firstOrNull { it.name == name }

/** One question of Help & FAQ and its answer. */
@Immutable
public data class HelpItem(val question: String, val answer: List<ProseBlock>)

public class AboutTexts @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    public suspend fun document(document: AboutDocument): List<ProseBlock> =
        withoutTitle(ProseMarkdown.parse(read(document.asset)))

    public suspend fun help(): List<HelpItem> = helpItems(ProseMarkdown.parse(read(HELP)))

    private suspend fun read(asset: String): String = withContext(io) {
        context.assets.open("$FOLDER/$asset").bufferedReader().use { it.readText() }
    }

    private companion object {
        const val FOLDER = "about"
        const val HELP = "help.md"
    }
}

/**
 * A document's own `# Title` is dropped: the page's title bar already says it,
 * and the same words twice at the top read as a mistake.
 */
internal fun withoutTitle(blocks: List<ProseBlock>): List<ProseBlock> =
    if ((blocks.firstOrNull() as? ProseBlock.Heading)?.level == 1) blocks.drop(1) else blocks

/** Each `## Question` and the blocks under it, up to the next question. */
internal fun helpItems(blocks: List<ProseBlock>): List<HelpItem> {
    val items = mutableListOf<HelpItem>()
    var question: String? = null
    val answer = mutableListOf<ProseBlock>()
    fun close() {
        question?.let { items += HelpItem(it, answer.toList()) }
        answer.clear()
    }
    for (block in blocks) {
        if (block is ProseBlock.Heading) {
            close()
            question = block.plainText
        } else if (question != null) {
            answer += block
        }
    }
    close()
    return items
}
