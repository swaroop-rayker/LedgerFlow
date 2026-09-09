package com.ledgerflow.feature.ocr.corpus

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * What the receipt corpus is made of, without disclosing what is in it.
 *
 * ## The split this test exists to police
 *
 * Receipt images **and their expected-output JSON** live in a private store
 * outside this repository. The images because a real Indian retail receipt
 * carries a card tail, often the customer's mobile number, a loyalty id and an
 * invoice number; the ground truth because it *is* the shopping list, and
 * structured and greppable it is arguably more revealing than the photograph.
 *
 * Redaction is not the alternative it is for the SMS corpus. There, swapping an
 * account tail leaves the regex an identical shape. Here every pixel edit is a
 * change to the recogniser's input, so a redacted corpus would have drifted from
 * reality invisibly — which is `SPEC.md` §16 Q15 wearing a new costume.
 *
 * So `testdata/receipts/manifest.json` is the public record: names, hashes,
 * provenance and counts. This test is what stops it becoming fiction.
 *
 * ## Skip locally, fail in CI
 *
 * A fresh clone without the private store must still build, so the store's
 * absence is an `assumeTrue` here. But **in CI it is a failure**: CI is the
 * environment that is supposed to have the corpus, and a gate that silently
 * does nothing is the failure this repository has now recorded five times
 * (`ExportCoversEveryTableTest`, the schema-directory task input, the
 * permission guard's blindness to the merged manifest, the APK-size glob).
 * Making absence pass everywhere would put this test in exactly that company.
 *
 * ## The ratchet
 *
 * [MINIMUM_REAL_RECEIPTS] **only ever goes up**, exactly as
 * `CorpusProvenanceTest`'s floors do. It is not a target to design toward; it
 * is what stops real fixtures being deleted or reclassified while the total
 * holds steady. Raise it when a receipt lands. Never lower it to make a change
 * pass.
 *
 * It starts at **0**, which is the honest count: no receipt has been captured
 * yet. Zero asserts nothing today and everything the moment someone removes the
 * first one.
 */
class ReceiptCorpusTest {

    private companion object {
        /** The ratchet. Only ever goes up. See the class KDoc. */
        const val MINIMUM_REAL_RECEIPTS = 0

        /**
         * Below these, `SPEC.md` §12's ≥90% figure is **provisional**.
         *
         * A ratio over eight receipts has error bars wide enough to drive
         * through, and calling that a met gate is the same category of claim as
         * an unmarked synthetic corpus. Read from the manifest rather than
         * duplicated, so there is one number and not two that can disagree.
         */
        const val MANIFEST = "testdata/receipts/manifest.json"

        val REQUIRED_FIELDS = listOf("name", "image", "imageSha256", "expectedSha256", "provenance")
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val repositoryRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Repository root not found from ${File("").absolutePath}")
    }

    private val manifest: JsonObject by lazy {
        val file = File(repositoryRoot, MANIFEST)
        check(file.isFile) { "$MANIFEST is missing. It is committed, not generated on demand." }
        json.parseToJsonElement(file.readText()).jsonObject
    }

    private val receipts: List<JsonObject> by lazy {
        manifest["receipts"]?.jsonArray?.map { it.jsonObject }.orEmpty()
    }

    private fun str(o: JsonObject, key: String): String? = o[key]?.jsonPrimitive?.content

    private fun int(o: JsonObject, key: String): Int? =
        o[key]?.jsonPrimitive?.content?.toIntOrNull()

    /**
     * The private store, or null.
     *
     * Three resolutions, documented in `testdata/receipts/README.md`. The
     * environment variable is what CI sets; `local.properties` is the
     * Android-idiomatic local override; the sibling directory is the
     * zero-configuration default.
     */
    private val corpusDir: File? by lazy {
        val fromEnv = System.getenv("LEDGERFLOW_RECEIPT_CORPUS")?.takeIf { it.isNotBlank() }
        val fromLocal = File(repositoryRoot, "local.properties")
            .takeIf { it.isFile }
            ?.let { file ->
                Properties().apply { file.inputStream().use(::load) }
                    .getProperty("ledgerflow.receiptCorpusDir")
            }
            ?.takeIf { it.isNotBlank() }
        val sibling = File(repositoryRoot.parentFile, "LedgerFlow-receipts")

        listOfNotNull(fromEnv?.let(::File), fromLocal?.let(::File), sibling).firstOrNull { it.isDirectory }
    }

    private val runningInCi: Boolean
        get() = System.getenv("CI").equals("true", ignoreCase = true)

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    // ── The public half: always runs, store or no store ─────────────────────

    /**
     * A malformed or hand-edited manifest would make everything below vacuous.
     */
    @Test
    fun theManifestIsWellFormed() {
        assertThat(manifest["version"]?.jsonPrimitive?.content).isEqualTo("1")
        assertThat(manifest["receipts"]).isNotNull()

        receipts.forEach { entry ->
            val name = str(entry, "name") ?: "<unnamed>"
            REQUIRED_FIELDS.forEach { field ->
                assertWithMessage("manifest entry '%s' is missing \"%s\"", name, field)
                    .that(str(entry, field))
                    .isNotNull()
            }
        }
    }

    /** Names are the join key to the private store; duplicates would mask a receipt. */
    @Test
    fun receiptNamesAreUnique() {
        val names = receipts.mapNotNull { str(it, "name") }
        assertThat(names).containsNoDuplicates()
    }

    @Test
    fun everyReceiptDeclaresItsProvenance() {
        receipts.forEach { entry ->
            assertWithMessage("receipt '%s' has no \"provenance\"", str(entry, "name"))
                .that(str(entry, "provenance"))
                .isAnyOf("real", "synthetic")
        }
    }

    /**
     * A real receipt says why it was safe to keep whole.
     *
     * The SMS corpus asks a real fixture to record *what was substituted*.
     * Nothing is substituted here — an image cannot be redacted without changing
     * what the recogniser reads — so the obligation becomes the other one:
     * state what made this particular receipt safe to retain unedited. Writing
     * that sentence is the moment someone notices a printed phone number.
     */
    @Test
    fun everyRealReceiptSaysWhyItWasSafeToKeep() {
        receipts.filter { str(it, "provenance") == "real" }.forEach { entry ->
            val safeBecause = str(entry, "safeBecause").orEmpty()
            assertWithMessage(
                "real receipt '%s' must record \"safeBecause\" — nothing is redacted in " +
                    "this corpus, so each fixture has to say what made it safe to keep whole",
                str(entry, "name"),
            ).that(safeBecause.length).isAtLeast(20)
        }
    }

    /** The floor. Only ever goes up. */
    @Test
    fun theRealReceiptCountNeverGoesDown() {
        val real = receipts.count { str(it, "provenance") == "real" }
        assertThat(real).isAtLeast(MINIMUM_REAL_RECEIPTS)
    }

    /**
     * States where the gate stands, every run, whether or not anyone asked.
     *
     * Not an assertion about the ratio — that belongs to the extractor's own
     * test, which does not exist yet. This one exists so "≥90%" is never quoted
     * without the sample size beside it, which is the difference between a
     * measurement and a slogan.
     */
    @Test
    fun theGateStandingIsReported() {
        val receiptFloor = int(manifest, "gradedReceiptsFloor") ?: error("no gradedReceiptsFloor")
        val lineFloor = int(manifest, "gradedItemLinesFloor") ?: error("no gradedItemLinesFloor")
        val graded = receipts.size
        val lines = receipts.sumOf { int(it, "itemLineCount") ?: 0 }
        val met = graded >= receiptFloor && lines >= lineFloor

        println(
            buildString {
                append("Receipt corpus: $graded receipts, $lines ITEM lines. ")
                append("SPEC.md §12 gate is ")
                append(if (met) "MEASURABLE" else "PROVISIONAL")
                append(" (floors: $receiptFloor receipts / $lineFloor lines).")
            },
        )

        // The floors are part of the specification, not a moving target.
        assertThat(receiptFloor).isEqualTo(25)
        assertThat(lineFloor).isEqualTo(300)
    }

    // ── The private half: needs the store ───────────────────────────────────

    /**
     * CI must be able to see the corpus.
     *
     * Locally this is a skip; in CI it is a failure. See the class KDoc — the
     * asymmetry is the whole point, and inverting it would make every assertion
     * below optional in the one place they matter.
     */
    @Test
    fun theCorpusIsReachableInCi() {
        if (!runningInCi) {
            println(
                "Receipt corpus: private store not required locally. " +
                    "Set LEDGERFLOW_RECEIPT_CORPUS or ledgerflow.receiptCorpusDir to run " +
                    "the content checks. See testdata/receipts/README.md.",
            )
            return
        }
        assertWithMessage(
            "CI must have the private receipt store. Set LEDGERFLOW_RECEIPT_CORPUS from " +
                "a checkout of the private corpus repository. A corpus gate that cannot " +
                "see the corpus passes, which is worse than failing.",
        ).that(corpusDir).isNotNull()
    }

    /**
     * The manifest describes the store, in both directions.
     *
     * A manifest checked only against itself is a restatement. This is the
     * assertion that makes it a record: every entry resolves to a real pair of
     * files whose bytes hash to what was recorded, and the store holds nothing
     * the manifest does not list.
     */
    @Test
    fun theManifestMatchesTheStore() {
        val dir = corpusDir
        assumeTrue("private receipt store not present", dir != null)
        requireNotNull(dir)

        receipts.forEach { entry ->
            val name = str(entry, "name").orEmpty()
            val image = File(dir, str(entry, "image").orEmpty())
            val expected = File(dir, "$name.json")

            assertWithMessage("receipt '%s': image missing from the store", name)
                .that(image.isFile).isTrue()
            assertWithMessage("receipt '%s': expected-output JSON missing from the store", name)
                .that(expected.isFile).isTrue()

            assertWithMessage("receipt '%s': image bytes do not match imageSha256", name)
                .that(sha256(image)).isEqualTo(str(entry, "imageSha256"))
            assertWithMessage(
                "receipt '%s': expected-output JSON does not match expectedSha256. If the " +
                    "ground truth was corrected, regenerate the manifest -- but check first " +
                    "that it was corrected against the IMAGE and not against the extractor.",
                name,
            ).that(sha256(expected)).isEqualTo(str(entry, "expectedSha256"))
        }

        val listed = receipts.mapNotNull { str(it, "name") }.toSet()
        val onDisk = dir.listFiles { f -> f.extension == "json" }
            .orEmpty()
            .map { it.nameWithoutExtension }
            .toSet()

        assertWithMessage(
            "the store holds receipts the manifest does not list -- regenerate it, or the " +
                "public record understates the corpus",
        ).that(onDisk - listed).isEmpty()
    }

    /** Money in a fixture is minor units. Law 3 applies to test data too. */
    @Test
    fun noFixtureCarriesADecimalAmount() {
        val dir = corpusDir
        assumeTrue("private receipt store not present", dir != null)
        requireNotNull(dir)

        val decimalMoney = Regex(""""(?:\w*[Mm]inor|billTotalMinor)"\s*:\s*-?\d+\.\d""")
        dir.listFiles { f -> f.extension == "json" }.orEmpty().forEach { file ->
            assertWithMessage(
                "fixture '%s' has a decimal in a *Minor field -- money is Long minor units " +
                    "(Law 3), in fixtures as much as in code",
                file.name,
            ).that(decimalMoney.containsMatchIn(file.readText())).isFalse()
        }
    }
}
