package com.ledgerflow.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.ledgerflow.core.database.entity.NotificationRawEntity
import com.ledgerflow.core.database.entity.PackageAllowlistEntity
import com.ledgerflow.core.database.entity.ParserRuleEntity
import com.ledgerflow.core.database.entity.PendingTransactionEntity
import com.ledgerflow.core.database.entity.SenderAllowlistEntity
import com.ledgerflow.core.database.entity.SmsRawEntity
import com.ledgerflow.core.model.PendingStatus
import com.ledgerflow.core.model.RawParseStatus
import kotlinx.coroutines.flow.Flow

/**
 * Captured SMS (SPEC.md §5.1). Schema v6.
 *
 * The insert is `IGNORE`, not `ABORT`: `body_hash` is unique and the network
 * genuinely re-delivers messages, so a duplicate is an expected event rather
 * than an error. `-1` back means "already had it", which the caller treats as
 * success — the message is captured, just not twice.
 */
@Dao
public interface SmsRawDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insert(row: SmsRawEntity): Long

    /** Rows the worker has not triaged yet, oldest first so ordering is stable. */
    @Query(
        "SELECT * FROM sms_raw WHERE parse_status = :status ORDER BY received_at LIMIT :limit",
    )
    public suspend fun withStatus(status: RawParseStatus, limit: Int): List<SmsRawEntity>

    @Query("SELECT * FROM sms_raw WHERE id = :id")
    public suspend fun byId(id: String): SmsRawEntity?

    @Query("UPDATE sms_raw SET parse_status = :status, matched_rule_id = :ruleId WHERE id = :id")
    public suspend fun updateStatus(id: String, status: RawParseStatus, ruleId: String?)

    /**
     * §3.1's `DUPLICATE_SUPPRESSED`, **without touching `matched_rule_id`**.
     *
     * A retroactive flip re-marks a row that was already `PARSED`, and which
     * rule parsed it is still true and still what the rule test bench reads.
     * Reusing [updateStatus] would need the caller to carry the rule id back
     * from a row it has no reason to have read.
     */
    @Query("UPDATE sms_raw SET parse_status = :status WHERE id = :id")
    public suspend fun updateStatusOnly(id: String, status: RawParseStatus)

    /**
     * D-09: the body goes, the row stays.
     *
     * Blanking rather than deleting, so the parse result and anything derived
     * from it keep their provenance. A body of `''` past the expiry means
     * "purged" — no SMS is captured with an empty body.
     */
    @Query(
        "UPDATE sms_raw SET body = '' WHERE body != '' AND retention_expires_at <= :now",
    )
    public suspend fun purgeExpiredBodies(now: Long): Int


    /**
     * Every row, for the backup export (ADR-0017, SPEC.md §5.9).
     *
     * `ORDER BY` a stable key so two exports of an unchanged database produce
     * byte-identical payloads — the round-trip test compares row sets, but a
     * user comparing two `.lfbk` fingerprints should not see them differ
     * because SQLite returned rows in a different order.
     */
    @Query("SELECT * FROM sms_raw ORDER BY id")
    public suspend fun all(): List<SmsRawEntity>

    /**
     * The restore path. `ABORT`, like every other table's: a restore runs inside
     * one transaction and a conflict must roll the whole thing back rather than
     * leave a partly-populated database (see `DatabaseBackupManager.restore`).
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAll(rows: List<SmsRawEntity>)

    @Query("SELECT COUNT(*) FROM sms_raw")
    public suspend fun count(): Int
}

/** Captured notifications (SPEC.md §5.2). Schema v6. See [SmsRawDao] on `IGNORE`. */
@Dao
public interface NotificationRawDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insert(row: NotificationRawEntity): Long

    @Query(
        "SELECT * FROM notification_raw WHERE parse_status = :status " +
            "ORDER BY posted_at LIMIT :limit",
    )
    public suspend fun withStatus(status: RawParseStatus, limit: Int): List<NotificationRawEntity>

    @Query("SELECT * FROM notification_raw WHERE id = :id")
    public suspend fun byId(id: String): NotificationRawEntity?

    @Query(
        "UPDATE notification_raw SET parse_status = :status, matched_rule_id = :ruleId " +
            "WHERE id = :id",
    )
    public suspend fun updateStatus(id: String, status: RawParseStatus, ruleId: String?)

    /** See [SmsRawDao.updateStatusOnly]. */
    @Query("UPDATE notification_raw SET parse_status = :status WHERE id = :id")
    public suspend fun updateStatusOnly(id: String, status: RawParseStatus)

    @Query(
        "UPDATE notification_raw SET body = '', title = NULL " +
            "WHERE body != '' AND retention_expires_at <= :now",
    )
    public suspend fun purgeExpiredBodies(now: Long): Int


    /**
     * Every row, for the backup export (ADR-0017, SPEC.md §5.9).
     *
     * `ORDER BY` a stable key so two exports of an unchanged database produce
     * byte-identical payloads — the round-trip test compares row sets, but a
     * user comparing two `.lfbk` fingerprints should not see them differ
     * because SQLite returned rows in a different order.
     */
    @Query("SELECT * FROM notification_raw ORDER BY id")
    public suspend fun all(): List<NotificationRawEntity>

    /**
     * The restore path. `ABORT`, like every other table's: a restore runs inside
     * one transaction and a conflict must roll the whole thing back rather than
     * leave a partly-populated database (see `DatabaseBackupManager.restore`).
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAll(rows: List<NotificationRawEntity>)

    @Query("SELECT COUNT(*) FROM notification_raw")
    public suspend fun count(): Int
}

/**
 * The packages LedgerFlow may read notifications from (D-10). Schema v6.
 *
 * **[isAllowed] is the privacy guarantee's implementation.** §5.2 requires the
 * check to run before any body access, so this is the first thing
 * `NotificationIngestService` calls and nothing reads `sbn.notification.extras`
 * until it returns true.
 */
@Dao
public interface PackageAllowlistDao {

    @Query(
        "SELECT COUNT(*) > 0 FROM package_allowlist " +
            "WHERE package_name = :packageName AND enabled = 1",
    )
    public suspend fun isAllowed(packageName: String): Boolean

    @Query("SELECT * FROM package_allowlist ORDER BY package_name")
    public fun observeAll(): Flow<List<PackageAllowlistEntity>>

    /**
     * Seeds or updates the curated list.
     *
     * `Upsert` would overwrite a user's `enabled = 0`, so the seeder inserts
     * only what is missing — see [insertMissing]. This exists for the Settings
     * screen, where an explicit write is the point.
     */
    @Upsert
    public suspend fun upsert(rows: List<PackageAllowlistEntity>)

    /**
     * The seeding path: adds packages the user has never seen, and leaves every
     * existing row exactly as it is.
     *
     * D-10 makes the curated list user-editable, which means a package the user
     * disabled must stay disabled across app updates. `IGNORE` on the primary
     * key is what makes re-running the seeder idempotent rather than a reset of
     * the user's choices.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insertMissing(rows: List<PackageAllowlistEntity>)


    /**
     * Every row, for the backup export (ADR-0017, SPEC.md §5.9).
     *
     * `ORDER BY` a stable key so two exports of an unchanged database produce
     * byte-identical payloads — the round-trip test compares row sets, but a
     * user comparing two `.lfbk` fingerprints should not see them differ
     * because SQLite returned rows in a different order.
     */
    @Query("SELECT * FROM package_allowlist ORDER BY package_name")
    public suspend fun all(): List<PackageAllowlistEntity>

    /**
     * The restore path. `ABORT`, like every other table's: a restore runs inside
     * one transaction and a conflict must roll the whole thing back rather than
     * leave a partly-populated database (see `DatabaseBackupManager.restore`).
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAll(rows: List<PackageAllowlistEntity>)

    @Query("SELECT COUNT(*) FROM package_allowlist")
    public suspend fun count(): Int
}

/**
 * Which SMS senders count as financial (SPEC.md §5.1). Schema v6.
 *
 * Matched with `GLOB`, not `=`: Indian sender IDs carry a rotating two-letter
 * operator prefix, so `VM-HDFCBK`, `AD-HDFCBK` and `JD-HDFCBK` are one bank and
 * a pattern like `*-HDFCBK` is the only thing that catches all of them. `GLOB`
 * rather than `LIKE` because it is case-sensitive and `*`/`?` behave the way the
 * patterns in the seed file are written; senders are normalised to upper case
 * before the comparison.
 *
 * **`GLOB` anchors the whole string, and that is what the v1 seed got wrong.**
 * A real TRAI DLT header is `XX-ENTITY-C`, with a trailing route class -- `T`
 * transactional, `S` service, `P` promotional, `G` government. `*-HDFCBK` is the
 * *entity* tail, so it matched none of the owner's actual messages: every bank
 * SMS on a real device was triaged `SENDER_NOT_ALLOWLISTED` and the app captured
 * nothing for two phases without anything failing. The seed carries both forms
 * from v2 -- the bare entity, and `*-HDFCBK-[^P]` for the suffixed header.
 * Promotional is excluded on purpose: §5.1 turns an allowlisted sender's
 * unparseable message into a `confidence = 0` PENDING row, so admitting `-P`
 * would make every bank marketing SMS an Inbox item to dismiss by hand.
 *
 * `[^...]` negation is SQLite's, verified on device rather than assumed --
 * `RawIngestRepositoryInstrumentedTest` asserts against real headers observed on
 * the owner's phone, and is instrumented for exactly that reason: a JVM test
 * would agree with whatever we believed `GLOB` meant.
 */
@Dao
public interface SenderAllowlistDao {

    @Query(
        "SELECT COUNT(*) > 0 FROM sender_allowlist " +
            "WHERE enabled = 1 AND :sender GLOB sender_pattern",
    )
    public suspend fun matches(sender: String): Boolean

    @Query("SELECT * FROM sender_allowlist ORDER BY sender_pattern")
    public fun observeAll(): Flow<List<SenderAllowlistEntity>>

    /** Seeding. See [PackageAllowlistDao.insertMissing] on why this is `IGNORE`. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insertMissing(rows: List<SenderAllowlistEntity>)


    /**
     * Every row, for the backup export (ADR-0017, SPEC.md §5.9).
     *
     * `ORDER BY` a stable key so two exports of an unchanged database produce
     * byte-identical payloads — the round-trip test compares row sets, but a
     * user comparing two `.lfbk` fingerprints should not see them differ
     * because SQLite returned rows in a different order.
     */
    @Query("SELECT * FROM sender_allowlist ORDER BY sender_pattern")
    public suspend fun all(): List<SenderAllowlistEntity>

    /**
     * The restore path. `ABORT`, like every other table's: a restore runs inside
     * one transaction and a conflict must roll the whole thing back rather than
     * leave a partly-populated database (see `DatabaseBackupManager.restore`).
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAll(rows: List<SenderAllowlistEntity>)

    @Query("SELECT COUNT(*) FROM sender_allowlist")
    public suspend fun count(): Int
}

/**
 * The versioned extraction ruleset (SPEC.md §5.1). Schema v6.
 *
 * Declared now because the table is; the engine that reads it is P2-3. Only the
 * loader's half exists here, so a ruleset can be seeded and inspected before
 * anything matches against it.
 */
@Dao
public interface ParserRuleDao {

    @Query(
        "SELECT * FROM parser_rule WHERE ruleset_version = :version AND enabled = 1 " +
            "ORDER BY priority, id",
    )
    public suspend fun enabledRules(version: Int): List<ParserRuleEntity>

    /**
     * Replaces the shipped rules for one ruleset version.
     *
     * Scoped to `is_user_defined = 0` so a ruleset bump never touches a rule the
     * user wrote (§5.1's rule editor) — that is the whole reason these live in a
     * table rather than only in the asset.
     */
    @Query("DELETE FROM parser_rule WHERE ruleset_version = :version AND is_user_defined = 0")
    public suspend fun deleteShippedRules(version: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertAll(rows: List<ParserRuleEntity>)


    /**
     * Every row, for the backup export (ADR-0017, SPEC.md §5.9).
     *
     * `ORDER BY` a stable key so two exports of an unchanged database produce
     * byte-identical payloads — the round-trip test compares row sets, but a
     * user comparing two `.lfbk` fingerprints should not see them differ
     * because SQLite returned rows in a different order.
     */
    @Query("SELECT * FROM parser_rule ORDER BY id")
    public suspend fun all(): List<ParserRuleEntity>

    @Query("SELECT COUNT(*) FROM parser_rule")
    public suspend fun count(): Int
}

/**
 * The approval queue (SPEC.md §5.1, §6.1). Schema v6, first written at P2-4.
 *
 * **This DAO is Law 1's holding pen.** Parsers, workers and receivers insert
 * here; nothing here is a ledger row, appears in a total or reaches a rollup.
 * The only thing that turns one of these into a `ledger_entry` is
 * `ApproveTransactionUseCase`, and it is guarded separately by
 * `LedgerSingleWriterTest` — P2-4 opens no door into `ledger_entry` and this
 * interface deliberately has no method that could.
 *
 * The insert is `ABORT`, not `IGNORE`: unlike a re-delivered SMS, a second
 * candidate for the same raw row is not an expected event, and the caller checks
 * [idForRawRef] inside the same transaction rather than letting a conflict
 * strategy absorb something nobody meant to happen.
 */
@Dao
public interface PendingTransactionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insert(row: PendingTransactionEntity)

    /**
     * The candidate this raw row already produced, if any — P2-4's idempotency
     * check.
     *
     * Unindexed on purpose, for now. `raw_ref_id` is nullable (manual and, at
     * P4, OCR candidates have no raw row) and a *unique* index on it would be
     * the strongest possible guarantee — SQLite treats NULLs as distinct, so it
     * would cost those rows nothing. It is not here because it needs schema v8
     * and a migration on a live device, and the transaction the caller wraps
     * this in already provides the property. **If P2-5 or P2-6 needs to look a
     * candidate up by raw row on a hot path, that index is the change to make**
     * rather than living with the scan.
     */
    @Query("SELECT id FROM pending_transaction WHERE raw_ref_id = :rawRefId LIMIT 1")
    public suspend fun idForRawRef(rawRefId: String): String?

    @Query("SELECT * FROM pending_transaction WHERE id = :id")
    public suspend fun byId(id: String): PendingTransactionEntity?

    /**
     * Candidates sharing a dedupe bucket inside §3.1's ±3 minute window — P2-5's
     * lookup.
     *
     * This is the statement `Index(dedupe_key, created_at)` exists for: an
     * equality on the key and a range on the time, served as one index scan.
     *
     * **Already-suppressed rows are excluded.** A row that lost a dedupe must
     * not go on to win one; a third arrival is compared against the row that
     * survived, and matching it transitively matches the rest of the group. That
     * also keeps `suppressed_by_id` one hop rather than a chain the Inbox would
     * have to walk.
     *
     * Every status is otherwise in scope, `APPROVED` included. If the user has
     * already approved the SMS candidate when the notification lands, the
     * notification is still a duplicate of it, and suppressing it is exactly
     * what stops the same payment reaching the ledger twice.
     */
    @Query(
        "SELECT * FROM pending_transaction " +
            "WHERE dedupe_key = :key AND suppressed_by_id IS NULL " +
            "AND created_at BETWEEN :from AND :to " +
            "ORDER BY created_at",
    )
    public suspend fun inDedupeWindow(
        key: String,
        from: Long,
        to: Long,
    ): List<PendingTransactionEntity>

    /**
     * Marks one candidate as the loser of a dedupe (§3.1).
     *
     * Scoped to `status = 'PENDING'`, and that predicate is load-bearing rather
     * than defensive. A retroactive flip -- the later, richer extraction winning
     * -- must never suppress a row the user has already **approved**: that row
     * has a `ledger_entry` behind it, and hiding its candidate would orphan the
     * only record of how the entry got there. An approved or discarded row has
     * been decided by a human and this statement affects no rows, which the
     * caller reads as "do not flip".
     */
    @Query(
        "UPDATE pending_transaction SET suppressed_by_id = :winnerId " +
            "WHERE id = :id AND status = 'PENDING' AND suppressed_by_id IS NULL",
    )
    public suspend fun suppress(id: String, winnerId: String): Int

    /** Newest first, as §6.1's `INDEX(status, created_at DESC)` serves. The Inbox reads this at P2-6. */
    @Query(
        "SELECT * FROM pending_transaction WHERE status = :status " +
            "ORDER BY created_at DESC LIMIT :limit",
    )
    public suspend fun withStatus(status: PendingStatus, limit: Int): List<PendingTransactionEntity>


    /**
     * Every row, for the backup export (ADR-0017, SPEC.md §5.9).
     *
     * `ORDER BY` a stable key so two exports of an unchanged database produce
     * byte-identical payloads — the round-trip test compares row sets, but a
     * user comparing two `.lfbk` fingerprints should not see them differ
     * because SQLite returned rows in a different order.
     */
    @Query("SELECT * FROM pending_transaction ORDER BY id")
    public suspend fun all(): List<PendingTransactionEntity>

    /**
     * The restore path. `ABORT`, like every other table's: a restore runs inside
     * one transaction and a conflict must roll the whole thing back rather than
     * leave a partly-populated database (see `DatabaseBackupManager.restore`).
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAll(rows: List<PendingTransactionEntity>)

    @Query("SELECT COUNT(*) FROM pending_transaction")
    public suspend fun count(): Int
}
