package com.ledgerflow.core.data.ingest

import android.content.Context
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.entity.NotificationRawEntity
import com.ledgerflow.core.database.entity.PackageAllowlistEntity
import com.ledgerflow.core.database.entity.SenderAllowlistEntity
import com.ledgerflow.core.database.entity.SmsRawEntity
import com.ledgerflow.core.domain.ingest.CaptureOutcome
import com.ledgerflow.core.domain.ingest.IngestSourceType
import com.ledgerflow.core.domain.ingest.RawIngestEvent
import com.ledgerflow.core.domain.ingest.RawIngestRepository
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
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : RawIngestRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun isPackageAllowed(packageName: String): Boolean = withContext(io) {
        // A locked vault cannot answer, and the honest answer is "no". Reading a
        // notification we could not then store would be the §5.2 rule broken for
        // nothing.
        runCatching { session.requireDatabase().packageAllowlistDao().isAllowed(packageName) }
            .getOrDefault(false)
    }

    override suspend fun isSenderAllowed(sender: String): Boolean = withContext(io) {
        runCatching {
            session.requireDatabase().senderAllowlistDao().matches(sender.uppercase())
        }.getOrDefault(false)
    }

    override suspend fun record(event: RawIngestEvent): CaptureOutcome = withContext(io) {
        val database = runCatching { session.requireDatabase() }.getOrNull()
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

    override suspend fun triageCapturedSms(limit: Int): Int = withContext(io) {
        val database = runCatching { session.requireDatabase() }.getOrNull() ?: return@withContext 0
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

    override suspend fun purgeExpiredBodies(): Int = withContext(io) {
        val database = runCatching { session.requireDatabase() }.getOrNull() ?: return@withContext 0
        val now = clock.nowMillis()
        runCatching {
            database.smsRawDao().purgeExpiredBodies(now) +
                database.notificationRawDao().purgeExpiredBodies(now)
        }.getOrDefault(0)
    }

    override suspend fun seedAllowlists(): Unit = withContext(io) {
        val database = runCatching { session.requireDatabase() }.getOrNull() ?: return@withContext

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
    private data class SenderSeed(val pattern: String, val label: String? = null)

    private companion object {
        val WHITESPACE = Regex("\\s+")

        /** D-09: 90 days, then the body goes and the row stays. */
        val RETENTION_MILLIS: Long = TimeUnit.DAYS.toMillis(90)

        const val PACKAGE_SEED = "ingest/package_allowlist.json"
        const val SENDER_SEED = "ingest/sender_allowlist.json"
    }
}
