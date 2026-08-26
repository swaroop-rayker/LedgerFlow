package com.ledgerflow.feature.ingest.parser

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractionField
import com.ledgerflow.core.domain.ingest.IngestSourceType
import com.ledgerflow.core.domain.ingest.InstrumentHint
import com.ledgerflow.core.domain.ingest.ParserRule
import com.ledgerflow.core.domain.ingest.RawIngestEvent
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * The golden corpus (CLAUDE.md §3, SPEC.md §12). **This is the parser's spec.**
 *
 * Every case is an input `.txt` and an expected `.json` under `testdata/`, run
 * against the *shipped* ruleset from `core/data/src/main/assets/parser_rules/`.
 * That pairing is the point: a unit test with a hand-written rule would prove
 * the engine can do what the test asked, and prove nothing about what the app
 * actually ships.
 *
 * **The corpus only grows** (CLAUDE.md §11). When a real bank SMS or UPI
 * notification fails to parse, it becomes a permanent fixture here — including
 * the ones that must parse to *nothing*, which are as important: an OTP from a
 * bank's own sender ID must not become a transaction, and nothing but a fixture
 * asserts that.
 *
 * **These starter fixtures are synthetic**, modelled on published formats. They
 * are enough to hold the engine's shape, and they are *not* the P2 exit
 * criterion — §13 asks for 50 SMS and 50 notifications, and §15.8 is explicit
 * that they must be real messages. A corpus of invented examples tests the rules
 * against the assumptions that produced them, which is circular. Real ones are
 * the only thing that closes it.
 */
class GoldenCorpusTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val repositoryRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Repository root not found from ${File("").absolutePath}")
    }

    /** The rules the app actually ships, not a fixture of them. */
    private val engine: ParserRuleEngine by lazy { ParserRuleEngine(shippedRules()) }

    private fun shippedRules(): List<ParserRule> {
        val asset = File(
            repositoryRoot,
            "core/data/src/main/assets/parser_rules/v1.json",
        )
        check(asset.isFile) { "Shipped ruleset not found at ${asset.path}" }

        val root = json.parseToJsonElement(asset.readText()).jsonObject
        val version = root["version"]!!.jsonPrimitive.content.toInt()
        return root["rules"]!!.jsonArray.map { element ->
            val rule = element.jsonObject
            ParserRule(
                id = rule.string("id"),
                rulesetVersion = version,
                priority = rule["priority"]!!.jsonPrimitive.content.toInt(),
                senderPattern = rule.string("senderPattern"),
                bodyPattern = rule.string("bodyPattern"),
                fieldMap = rule["fieldMap"]!!.jsonObject.entries.associate { (field, group) ->
                    val known = ExtractionField.fromWireName(field)
                    // A typo in the shipped ruleset would otherwise map nothing
                    // and look like a rule that simply does not extract much.
                    checkNotNull(known) { "Unknown extraction field '$field' in rule ${rule.string("id")}" }
                    known to group.jsonPrimitive.content
                },
                direction = rule["direction"]?.takeIf { it != JsonNull }
                    ?.jsonPrimitive?.content?.let(ExtractedDirection::valueOf),
                instrumentHint = rule["instrumentHint"]?.takeIf { it != JsonNull }
                    ?.jsonPrimitive?.content?.let(InstrumentHint::valueOf),
                confidenceBase = rule["confidenceBase"]!!.jsonPrimitive.content.toDouble(),
                enabled = rule["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
        }
    }

    private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content

    private fun casesIn(directory: String): List<File> =
        File(repositoryRoot, directory).listFiles { file -> file.extension == "txt" }
            ?.sortedBy { it.name }
            .orEmpty()

    /** A silent zero here would make every assertion below vacuous. */
    @Test
    fun theCorpusIsDiscoverable() {
        assertThat(casesIn("testdata/sms")).isNotEmpty()
        assertThat(casesIn("testdata/notifications")).isNotEmpty()
        assertThat(engine.usableRuleCount).isAtLeast(MINIMUM_RULES)
    }

    /**
     * Every shipped rule compiles.
     *
     * The engine drops a rule whose regex does not compile rather than throwing
     * per message — one bad user-written rule must not stop the others. That is
     * right at runtime and dangerous in the *shipped* set, where a broken rule
     * would silently stop matching and every affected message would quietly
     * become `needs_manual_fill`.
     */
    @Test
    fun everyShippedRuleCompiles() {
        assertThat(engine.usableRuleCount).isEqualTo(shippedRules().count { it.enabled })
    }

    @Test
    fun smsCorpusExtractsWhatIsExpected() {
        runCorpus("testdata/sms") { case ->
            RawIngestEvent(
                sourceType = IngestSourceType.SMS,
                sender = case.string("sender"),
                body = case.body,
                receivedAt = CAPTURED_AT,
            )
        }
    }

    @Test
    fun notificationCorpusExtractsWhatIsExpected() {
        runCorpus("testdata/notifications") { case ->
            RawIngestEvent(
                sourceType = IngestSourceType.NOTIFICATION,
                sender = case.string("sender"),
                body = case.body,
                receivedAt = CAPTURED_AT,
                packageName = case.string("packageName"),
            )
        }
    }

    /**
     * Every shipped rule is exercised by at least one fixture.
     *
     * A rule nobody tests is a rule that can rot silently — and worse, one whose
     * pattern can be broadened by a later edit until it starts swallowing other
     * banks' messages, with nothing to notice. This does not assert coverage of
     * every *branch*, only that no rule is dead.
     */
    @Test
    fun everyShippedRuleIsExercisedByTheCorpus() {
        val exercised = buildSet {
            (casesIn("testdata/sms") + casesIn("testdata/notifications")).forEach { file ->
                val case = Case(file, json)
                val event = if (case.packageNameOrNull != null) {
                    RawIngestEvent(
                        IngestSourceType.NOTIFICATION, case.string("sender"), case.body,
                        CAPTURED_AT, case.packageNameOrNull,
                    )
                } else {
                    RawIngestEvent(IngestSourceType.SMS, case.string("sender"), case.body, CAPTURED_AT)
                }
                (engine.extract(event) as? ExtractionResult.Matched)?.let { add(it.ruleId) }
            }
        }

        val shipped = shippedRules().filter { it.enabled }.map { it.id }
        assertThat(exercised).containsAtLeastElementsIn(shipped)
    }

    private fun runCorpus(directory: String, toEvent: (Case) -> RawIngestEvent) {
        val cases = casesIn(directory).map { Case(it, json) }
        assertThat(cases).isNotEmpty()

        cases.forEach { case ->
            val result = engine.extract(toEvent(case))
            val expected = case.expected

            if (expected == null) {
                // The fixture says this must extract nothing. An OTP, a promo,
                // a balance alert -- all from senders that also send real
                // transactions, which is exactly why they need asserting.
                assertThat(result).isEqualTo(ExtractionResult.Unmatched)
                return@forEach
            }

            assertThat(result).isInstanceOf(ExtractionResult.Matched::class.java)
            val matched = result as ExtractionResult.Matched
            val actual = matched.extracted

            assertThat(matched.ruleId).isEqualTo(expected.string("ruleId"))
            assertThat(actual.amount?.minor)
                .isEqualTo(expected["amountMinor"]?.jsonPrimitive?.content?.toLong())
            assertThat(actual.currency).isEqualTo(expected["currency"]?.jsonPrimitive?.content)
            assertThat(actual.direction.name).isEqualTo(expected.string("direction"))

            expected["merchantRaw"]?.let {
                assertThat(actual.merchantRaw).isEqualTo(it.jsonPrimitive.content)
            }
            expected["accountLast4"]?.let {
                assertThat(actual.accountLast4).isEqualTo(it.jsonPrimitive.content)
            }
            expected["instrumentHint"]?.let {
                assertThat(actual.instrumentHint.name).isEqualTo(it.jsonPrimitive.content)
            }
            expected["referenceNo"]?.let {
                assertThat(actual.referenceNo).isEqualTo(it.jsonPrimitive.content)
            }
            // Compared as a local date, not an epoch: the parser reads a local
            // date because a bank message states one, so an epoch expectation
            // would be a different number on a machine in another zone -- and a
            // fixture that only passes in IST is not a fixture.
            expected["occurredAtLocalDate"]?.let {
                val actualDate = actual.occurredAt?.let { millis ->
                    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                }
                assertThat(actualDate?.toString()).isEqualTo(it.jsonPrimitive.content)
            }
        }
    }

    /** One fixture: the `.txt` body and its `.json` expectations. */
    private class Case(txt: File, json: Json) {
        val name: String = txt.nameWithoutExtension
        val body: String = txt.readText().trimEnd('\n', '\r')

        private val meta: JsonObject = json
            .parseToJsonElement(File(txt.parentFile, "$name.json").readText())
            .jsonObject

        val expected: JsonObject? = meta["expected"]?.takeIf { it != JsonNull }?.jsonObject
        val packageNameOrNull: String? = meta["packageName"]?.jsonPrimitive?.content

        fun string(key: String): String = meta[key]!!.jsonPrimitive.content
    }

    private companion object {
        /** Arbitrary and fixed. Nothing in the corpus asserts on the capture time. */
        const val CAPTURED_AT = 1_700_000_000_000L
        const val MINIMUM_RULES = 5
    }
}

private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content
