package com.ledgerflow.core.data.ingest

import android.content.Context
import androidx.room.withTransaction
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.database.dao.PendingTransactionDao
import com.ledgerflow.core.database.entity.NotificationRawEntity
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.database.entity.PackageAllowlistEntity
import com.ledgerflow.core.database.entity.ParserRuleEntity
import com.ledgerflow.core.database.entity.PendingTransactionEntity
import com.ledgerflow.core.database.entity.SenderAllowlistEntity
import com.ledgerflow.core.database.entity.SmsRawEntity
import com.ledgerflow.core.domain.ingest.CaptureOutcome
import com.ledgerflow.core.domain.ingest.CapturedEvent
import com.ledgerflow.core.domain.ingest.DedupeKey
import com.ledgerflow.core.domain.ingest.DuplicateMatcher
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractionField
import com.ledgerflow.core.domain.ingest.IngestSourceType
import com.ledgerflow.core.domain.ingest.InstrumentHint
import com.ledgerflow.core.domain.ingest.ParserRule
import com.ledgerflow.core.domain.ingest.PendingCandidate
import com.ledgerflow.core.domain.ingest.PendingWriteOutcome
import com.ledgerflow.core.domain.ingest.RawIngestEvent
import com.ledgerflow.core.domain.ingest.RawIngestRepository
import com.ledgerflow.core.model.PendingStatus
import com.ledgerflow.core.model.RawParseStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The raw capture tables (SPEC.md §5.1, §5.2). Schema v6.
 *
 * **Nothing here parses, judges or joins.** Its whole job is to get a captured
 * message onto disk before anything looks at it, because §5.1's promise that a
 * financial SMS is never silently dropped only holds if the row exists before
 * the first decision is made about it.
 *
 * Every method returns rather than throws. A capture adapter has ~10 seconds and
 * no recovery (CLAUDE.md §7): an exception propagating out of here would take a
 * `BroadcastReceiver` with it, and the user would experience that as LedgerFlow
 * crashing whenever a text arrives.
 */
@Singleton
public class DefaultRawIngestRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val session: VaultSession,
    private val clock: Clock,
    private val ids: Uuid7Generator,
    private val candidateWriter: PendingCandidateWriter,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : RawIngestRepository {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The vault, opened if the UI has not already done it (§5.1).
     *
     * Every entry point in this class can be reached with no Activity alive: a
     * `BroadcastReceiver` for an SMS, a `NotificationListenerService`, or
     * `ParseIngestWorker` waking on its own. Before this, each of them asked for
     * a database that only the UI ever opened, and a message arriving with the
     * app closed was logged and lost.
     *
     * Null is a real answer and never a reason to invent one: the vault cannot
     * be opened, so there is nowhere to write, and the caller says so.
     */
    private suspend fun openVault(): LedgerFlowDatabase? = session.openForBackgroundWork()

    override suspend fun isPackageAllowed(packageName: String): Boolean = withContext(io) {
        // A locked vault cannot answer, and the honest answer is "no". Reading a
        // notification we could not then store would be the §5.2 rule broken for
        // nothing.
        val database = openVault() ?: return@withContext false
        runCatching { database.packageAllowlistDao().isAllowed(packageName) }
            .getOrDefault(false)
    }

    override suspend fun isSenderAllowed(sender: String): Boolean = withContext(io) {
        val database = openVault() ?: return@withContext false
        runCatching {
            database.senderAllowlistDao().matches(sender.uppercase())
        }.getOrDefault(false)
    }

    override suspend fun record(event: RawIngestEvent): CaptureOutcome = withContext(io) {
        val database = openVault()
            ?: return@withContext CaptureOutcome.Failed("vault is locked")

        val id = ids.generate()
        val hash = event.bodyHash()
        val expiresAt = event.receivedAt + RETENTION_MILLIS

        runCatching {
            when (event.sourceType) {
                IngestSourceType.SMS -> database.smsRawDao().insert(
                    SmsRawEntity(
                        id = id,
                        sender = event.sender,
                        body = event.body,
                        bodyHash = hash,
                        receivedAt = event.receivedAt,
                        // The platform reports the SIM on the intent, which the
                        // adapter does not currently read. Null is honest; a
                        // fabricated 0 would say "SIM 1" about every message.
                        simSlot = null,
                        parseStatus = RawParseStatus.CAPTURED,
                        matchedRuleId = null,
                        retentionExpiresAt = expiresAt,
                    ),
                )

                IngestSourceType.NOTIFICATION -> database.notificationRawDao().insert(
                    NotificationRawEntity(
                        id = id,
                        // Non-null for a notification by construction: the
                        // adapter cannot have reached here without the package
                        // it just allowlisted.
                        packageName = event.packageName.orEmpty(),
                        title = event.title,
                        body = event.body,
                        bodyHash = hash,
                        postedAt = event.receivedAt,
                        parseStatus = RawParseStatus.CAPTURED,
                        matchedRuleId = null,
                        retentionExpiresAt = expiresAt,
                    ),
                )
            }
        }.fold(
            onSuccess = { rowId ->
                // -1 is the unique body_hash refusing a re-delivery. Expected,
                // not an error: the message is captured, just not twice.
                if (rowId == -1L) CaptureOutcome.AlreadySeen else CaptureOutcome.Recorded(id)
            },
            onFailure = { CaptureOutcome.Failed(it.message ?: it::class.java.name) },
        )
    }

    override suspend fun capturedEvents(limit: Int): List<CapturedEvent> = withContext(io) {
        val database = openVault()
            ?: return@withContext emptyList()

        runCatching {
            val sms = database.smsRawDao().withStatus(RawParseStatus.CAPTURED, limit)
                .map { row ->
                    CapturedEvent(
                        rawId = row.id,
                        event = RawIngestEvent(
                            sourceType = IngestSourceType.SMS,
                            sender = row.sender,
                            body = row.body,
                            receivedAt = row.receivedAt,
                        ),
                    )
                }
            val notifications = database.notificationRawDao()
                .withStatus(RawParseStatus.CAPTURED, limit)
                .map { row ->
                    CapturedEvent(
                        rawId = row.id,
                        event = RawIngestEvent(
                            sourceType = IngestSourceType.NOTIFICATION,
                            sender = row.packageName,
                            body = row.body,
                            receivedAt = row.postedAt,
                            packageName = row.packageName,
                            title = row.title,
                        ),
                    )
                }
            (sms + notifications).sortedBy { it.event.receivedAt }
        }.getOrDefault(emptyList())
    }

    override suspend fun triageCapturedSms(limit: Int): Int = withContext(io) {
        val database = openVault() ?: return@withContext 0
        val dao = database.smsRawDao()

        val captured = runCatching { dao.withStatus(RawParseStatus.CAPTURED, limit) }
            .getOrDefault(emptyList())

        var filtered = 0
        captured.forEach { row ->
            if (!isSenderAllowed(row.sender)) {
                // Marked, not deleted. §5.1 keeps the record; D-09's retention
                // is what eventually takes the body.
                runCatching {
                    dao.updateStatus(row.id, RawParseStatus.SENDER_NOT_ALLOWLISTED, null)
                }
                filtered++
            }
            // An allowlisted sender is deliberately left at CAPTURED. There is
            // no ruleset yet (P2-3), and giving a row a verdict nothing produced
            // would be worse than leaving it in flight.
        }
        filtered
    }

    override suspend fun retriageRejectedSms(limit: Int): Int = withContext(io) {
        val database = openVault() ?: return@withContext 0

        runCatching {
            val fingerprint = senderAllowlistFingerprint(database)
            val seen = database.appMetaDao()
                .value(AppMetaEntity.KEY_SENDER_ALLOWLIST_FINGERPRINT)

            // Unchanged since the last pass, so no rejected row can have become
            // admissible and there is nothing to scan. This early return is what
            // keeps the sweep off every worker run: the rejected pile is the
            // user's ordinary personal SMS and grows without bound, and
            // re-checking all of it each time the phone receives a text would be
            // the expensive kind of thorough.
            //
            // A null marker means "never checked", which counts as changed. That
            // is the upgrade path onto the v2 seed that fixed the DLT-suffix
            // defect, and it is what recovers messages rejected before it.
            if (seen == fingerprint) return@runCatching 0

            val dao = database.smsRawDao()
            val rejected = dao.rejectedWithBody(RawParseStatus.SENDER_NOT_ALLOWLISTED, limit)

            var readmitted = 0
            rejected.forEach { row ->
                if (isSenderAllowed(row.sender)) {
                    // Back to CAPTURED, not straight to a candidate: the row
                    // rejoins the queue where it left it, and the rule engine
                    // and §5.1's never-drop rule apply to it exactly as they
                    // would to a message that had just arrived.
                    dao.updateStatusOnly(row.id, RawParseStatus.CAPTURED)
                    readmitted++
                }
            }

            // Only once the backlog is drained. A full page means there may be
            // more behind it, and advancing the marker here would strand
            // whatever this pass did not reach -- permanently, since nothing
            // else would ever look at those rows again.
            if (rejected.size < limit) {
                database.appMetaDao().put(
                    AppMetaEntity(AppMetaEntity.KEY_SENDER_ALLOWLIST_FINGERPRINT, fingerprint),
                )
            }
            readmitted
        }.getOrDefault(0)
    }

    /**
     * A stable digest of the enabled sender patterns (§16 Q14).
     *
     * Fingerprinting the patterns rather than hooking every edit is what makes
     * one mechanism serve both causes -- a shipped seed gaining patterns, and a
     * user adding their bank in Settings at P5. An edit hook has to be
     * remembered at each new call site; a value derived from the thing that
     * changed cannot be forgotten.
     *
     * Truncated to 16 hex characters. This is a change detector, not a security
     * boundary: it is compared against a value this app wrote to its own
     * encrypted database, and 64 bits is far past the point where an accidental
     * collision between two allowlists is worth thinking about.
     */
    private suspend fun senderAllowlistFingerprint(database: LedgerFlowDatabase): String {
        val patterns = database.senderAllowlistDao().enabledPatterns()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(patterns.joinToString("\u0000").toByteArray())
        return digest.take(FINGERPRINT_BYTES).joinToString("") { "%02x".format(it) }
    }

    override suspend fun purgeExpiredBodies(): Int = withContext(io) {
        val database = openVault() ?: return@withContext 0
        val now = clock.nowMillis()
        runCatching {
            database.smsRawDao().purgeExpiredBodies(now) +
                database.notificationRawDao().purgeExpiredBodies(now)
        }.getOrDefault(0)
    }

    override suspend fun seedAllowlists(): Unit = withContext(io) {
        val database = openVault() ?: return@withContext

        runCatching {
            val packages = readAsset<PackageSeedFile>(PACKAGE_SEED)
            database.packageAllowlistDao().insertMissing(
                packages.packages.map {
                    PackageAllowlistEntity(
                        packageName = it.packageName,
                        label = it.label,
                        enabled = true,
                    )
                },
            )

            val senders = readAsset<SenderSeedFile>(SENDER_SEED)
            database.senderAllowlistDao().insertMissing(
                senders.senders.map {
                    SenderAllowlistEntity(
                        senderPattern = it.pattern,
                        label = it.label,
                        enabled = true,
                    )
                },
            )
        }
        Unit
    }

    override suspend fun seedParserRules(): Unit = withContext(io) {
        val database = openVault() ?: return@withContext
        val dao = database.parserRuleDao()

        runCatching {
            val file = readAsset<RuleSeedFile>(RULES_SEED)
            // Shipped rules only. A rule the user wrote survives every ruleset
            // load -- that is the whole reason these live in a table as well as
            // in the asset (§5.1's rule editor).
            dao.deleteShippedRules(file.version)
            dao.insertAll(
                file.rules.map { rule ->
                    ParserRuleEntity(
                        id = rule.id,
                        rulesetVersion = file.version,
                        priority = rule.priority,
                        senderPattern = rule.senderPattern,
                        bodyPattern = rule.bodyPattern,
                        fieldMapJson = json.encodeToString(rule.fieldMap),
                        direction = rule.direction,
                        instrumentHint = rule.instrumentHint,
                        confidenceBase = rule.confidenceBase,
                        enabled = rule.enabled,
                        isUserDefined = false,
                    )
                },
            )
        }
        Unit
    }

    override suspend fun parserRules(): List<ParserRule> = withContext(io) {
        val database = openVault()
            ?: return@withContext emptyList()

        runCatching {
            database.parserRuleDao().enabledRules(RULESET_VERSION).mapNotNull(::toDomainRule)
        }.getOrDefault(emptyList())
    }

    override suspend fun recordParseOutcome(
        rawId: String,
        ruleId: String?,
        candidate: PendingCandidate,
    ): PendingWriteOutcome = withContext(io) {
        val database = openVault()
            ?: return@withContext PendingWriteOutcome.Failed("vault is locked")
        candidateWriter.write(
            database = database,
            refId = rawId,
            candidate = candidate,
            // A message capture left a row in `sms_raw` or
            // `notification_raw`, and the verdict belongs on it.
            verdict = PendingCandidateWriter.RawVerdict(ruleId),
        )
    }

    override suspend fun recordOcrCandidate(
        attachmentId: String,
        candidate: PendingCandidate,
    ): PendingWriteOutcome = withContext(io) {
        val database = openVault()
            ?: return@withContext PendingWriteOutcome.Failed("vault is locked")
        candidateWriter.write(
            database = database,
            refId = attachmentId,
            candidate = candidate,
            // **No raw row exists for a receipt.** `raw_ref_id` points at the
            // `attachment` instead, and there is no `parse_status` to stamp.
            // That is the only difference between the two callers -- the
            // writer is shared, so OCR gets §3.1's cross-source dedupe by
            // construction rather than by a second implementation that could
            // disagree.
            verdict = null,
        )
    }

    private fun toDomainRule(entity: ParserRuleEntity): ParserRule? = runCatching {
        val fieldMap = json.decodeFromString<Map<String, String>>(entity.fieldMapJson)
            .mapNotNull { (field, group) ->
                ExtractionField.fromWireName(field)?.let { it to group }
            }
            .toMap()

        ParserRule(
            id = entity.id,
            rulesetVersion = entity.rulesetVersion,
            priority = entity.priority,
            senderPattern = entity.senderPattern,
            bodyPattern = entity.bodyPattern,
            fieldMap = fieldMap,
            direction = entity.direction?.let { name ->
                ExtractedDirection.entries.firstOrNull { it.name == name }
            },
            instrumentHint = entity.instrumentHint?.let { name ->
                InstrumentHint.entries.firstOrNull { it.name == name }
            },
            confidenceBase = entity.confidenceBase,
            enabled = entity.enabled,
            isUserDefined = entity.isUserDefined,
        )
    }.getOrNull()

    private inline fun <reified T> readAsset(path: String): T =
        context.assets.open(path).use { stream ->
            json.decodeFromString<T>(stream.readBytes().decodeToString())
        }

    /**
     * §5.1's capture-time dedupe key: sender, normalized body, minute bucket.
     *
     * The minute bucket is what makes this catch a re-delivery without also
     * catching a genuine second identical payment an hour later — two ₹50
     * top-ups on the same day are two transactions and must both survive.
     *
     * Distinct from §3.1's *cross-source* dedupe key, which is computed from
     * extracted fields and lands with the parser. This one needs nothing but the
     * bytes, which is the point: it runs at capture, before anything is parsed.
     */
    private fun RawIngestEvent.bodyHash(): String {
        val bucket = receivedAt / TimeUnit.MINUTES.toMillis(1)
        val origin = packageName ?: sender
        val normalized = body.trim().replace(WHITESPACE, " ")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$origin|$normalized|$bucket".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Serializable
    private data class PackageSeedFile(val version: Int, val packages: List<PackageSeed>)

    @Serializable
    private data class PackageSeed(val packageName: String, val label: String? = null)

    @Serializable
    private data class SenderSeedFile(val version: Int, val senders: List<SenderSeed>)

    @Serializable
    private data class RuleSeedFile(val version: Int, val rules: List<RuleSeed>)

    @Serializable
    private data class RuleSeed(
        val id: String,
        val priority: Int,
        val senderPattern: String,
        val bodyPattern: String,
        val fieldMap: Map<String, String>,
        val direction: String? = null,
        val instrumentHint: String? = null,
        val confidenceBase: Double,
        val enabled: Boolean = true,
    )

    @Serializable
    private data class SenderSeed(val pattern: String, val label: String? = null)

    private companion object {
        val WHITESPACE = Regex("\\s+")

        /**
         * 64 bits of the allowlist digest (§16 Q14).
         *
         * A change detector, not a security boundary: it is compared against a
         * value this app wrote to its own encrypted database, and an accidental
         * collision between two allowlists at this width is not worth the
         * column it would take to avoid.
         */
        const val FINGERPRINT_BYTES = 8

        /** D-09: 90 days, then the body goes and the row stays. */
        val RETENTION_MILLIS: Long = TimeUnit.DAYS.toMillis(90)

        /** The ruleset this build ships and reads back (§5.1). */
        const val RULESET_VERSION = 1

        const val PACKAGE_SEED = "ingest/package_allowlist.json"
        const val SENDER_SEED = "ingest/sender_allowlist.json"
        const val RULES_SEED = "parser_rules/v1.json"
    }
}
