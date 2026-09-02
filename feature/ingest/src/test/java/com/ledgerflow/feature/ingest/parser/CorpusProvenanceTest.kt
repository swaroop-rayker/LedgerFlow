package com.ledgerflow.feature.ingest.parser

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * What the golden corpus is made of, and what a *real* fixture owes (SPEC.md
 * §13, §16 Q15).
 *
 * ## Why provenance is a field and not a convention
 *
 * §16 Q15 is the reason this file exists. Every SMS fixture supplied a sender
 * the allowlist had been written against, so the allowlist matched **no real
 * Indian bank** for three whole steps while every test stayed green. A synthetic
 * fixture tests the parser against the assumptions that produced it; the corpus
 * cannot tell you how much of itself is circular unless each case says so.
 *
 * The owner's decision at P2-9 was a **mixed corpus with the real ones marked**,
 * rather than real-only (which would have put P2's exit months away) or silence
 * about the difference (which is how Q15 happened). Marking is what makes the
 * mix honest, so it is enforced rather than asked for.
 *
 * ## The ratchet
 *
 * [MINIMUM_REAL_SMS] and [MINIMUM_REAL_NOTIFICATIONS] **only ever go up.** They
 * are not a target to design toward — they are a floor that stops the real
 * fixtures being deleted or quietly reclassified while the total stays at 50.
 * Raise them when a real message lands; never lower them to make a change pass.
 *
 * `MINIMUM_REAL_NOTIFICATIONS` is **0**, and that is not an oversight. Zero is
 * the true count: notification ingest has never captured a real message in this
 * project's life. A floor of zero asserts nothing today and everything the
 * moment someone deletes the first real one, and writing the honest number down
 * is worth more than a comfortable one.
 *
 * ## What a real fixture owes
 *
 * A real bank SMS contains the owner's account digits, a payee's name and a UPI
 * reference. This repository is public. So a fixture claiming `real` must carry
 * a note recording **what was substituted** — every one of the four that exist
 * already does, and the guard exists so the fifth does too, written by whoever
 * is pasting a message off their own phone at the time.
 */
class CorpusProvenanceTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val repositoryRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Repository root not found from ${File("").absolutePath}")
    }

    private fun metaIn(directory: String): List<Pair<String, kotlinx.serialization.json.JsonObject>> =
        File(repositoryRoot, directory)
            .listFiles { file -> file.extension == "json" }
            ?.sortedBy { it.name }
            ?.map { it.nameWithoutExtension to json.parseToJsonElement(it.readText()).jsonObject }
            .orEmpty()

    private fun provenanceOf(meta: kotlinx.serialization.json.JsonObject): String? =
        meta["provenance"]?.jsonPrimitive?.content

    private fun realCount(directory: String) =
        metaIn(directory).count { provenanceOf(it.second) == "real" }

    /** A silent zero would make every assertion below vacuous. */
    @Test
    fun theCorpusIsDiscoverable() {
        assertThat(metaIn("testdata/sms")).isNotEmpty()
        assertThat(metaIn("testdata/notifications")).isNotEmpty()
    }

    /**
     * Every fixture declares where it came from.
     *
     * Unlabelled is the state Q15 left the corpus in, and it is the one state
     * that makes the other assertions here unanswerable.
     */
    @Test
    fun everyFixtureDeclaresItsProvenance() {
        (metaIn("testdata/sms") + metaIn("testdata/notifications")).forEach { (name, meta) ->
            assertWithMessage("fixture '%s' has no \"provenance\"", name)
                .that(provenanceOf(meta))
                .isAnyOf("real", "synthetic")
        }
    }

    /**
     * A real fixture records what was redacted out of it.
     *
     * This repository is public and a real bank message is not anonymous: it
     * carries an account tail, a counterparty's name and a reference number. The
     * four that exist all say what was substituted; this is what makes the fifth
     * say it too.
     */
    @Test
    fun everyRealFixtureRecordsWhatWasSubstituted() {
        (metaIn("testdata/sms") + metaIn("testdata/notifications"))
            .filter { provenanceOf(it.second) == "real" }
            .forEach { (name, meta) ->
                val note = meta["note"]?.jsonPrimitive?.content.orEmpty()
                assertWithMessage(
                    "real fixture '%s' must have a note saying what was substituted " +
                        "-- this repo is public and a real bank message is not anonymous",
                    name,
                ).that(note.contains("substitut", ignoreCase = true))
                    .isTrue()
            }
    }

    /**
     * The floor. See the class KDoc: this only ever goes up.
     *
     * Asserted as `isAtLeast` rather than an equality so that adding a real
     * fixture is frictionless — the friction belongs on *removing* one.
     */
    @Test
    fun theRealFixtureCountNeverGoesDown() {
        assertThat(realCount("testdata/sms")).isAtLeast(MINIMUM_REAL_SMS)
        assertThat(realCount("testdata/notifications")).isAtLeast(MINIMUM_REAL_NOTIFICATIONS)
    }

    /**
     * §13's P2 exit criterion, as a number.
     *
     * Deliberately separate from the ratchet above: this one says the corpus is
     * *large enough*, that one says it is *honest enough*, and passing the first
     * while failing the second is exactly the outcome Q15 warns about.
     */
    @Test
    fun theCorpusMeetsTheP2ExitSize() {
        assertThat(metaIn("testdata/sms").size).isAtLeast(P2_EXIT_SMS)
        assertThat(metaIn("testdata/notifications").size).isAtLeast(P2_EXIT_NOTIFICATIONS)
    }

    private companion object {
        /** §13. Fifty of each. */
        const val P2_EXIT_SMS = 50
        const val P2_EXIT_NOTIFICATIONS = 50

        /**
         * Four real SMS, all HDFC, captured from the owner's phone across
         * 2026-08-26/27. **Raise this when a real one lands. Never lower it.**
         */
        const val MINIMUM_REAL_SMS = 4

        /**
         * Zero, and honestly so — notification ingest has never captured a real
         * message. Access was only granted at P2-8; `TESTING.md` F23 is the row
         * that changes this, and it needs one real payment.
         */
        const val MINIMUM_REAL_NOTIFICATIONS = 0
    }
}
