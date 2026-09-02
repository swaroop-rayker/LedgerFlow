package com.ledgerflow.core.data.analytics

import androidx.room.withTransaction
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.database.entity.DailyRollupEntity
import com.ledgerflow.core.domain.analytics.RollupRepository
import com.ledgerflow.core.model.LedgerType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Reconciliation (ADR-0006).
 *
 * **The same routine the incremental path uses, with the date range widened to
 * everything.** That is the point of ADR-0006's choice: reconciliation is not a
 * second opinion computed a different way, so it cannot disagree about
 * *method* — only about staleness, and staleness has exactly one correct
 * resolution.
 *
 * **It runs over the whole table, not a window.** A 5Y ledger is a few thousand
 * rows and one `GROUP BY` scan; a windowed pass would leave old drift
 * permanently unrepaired to save work nothing needs saved.
 *
 * **Repairs are counted, not announced.** Silently repairing would mask a
 * systematic bug in the incremental path with the very mechanism meant to catch
 * it, so the count goes to `app_meta` for the P5 diagnostics screen. It does not
 * go to the user: the condition has already healed and there is nothing for them
 * to do, which is the opposite of the listener-health banner (ADR-0020), where
 * only the user can act.
 */
@Singleton
public class DefaultRollupRepository @Inject constructor(
    private val session: VaultSession,
    private val clock: Clock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : RollupRepository {

    override suspend fun reconcile(): Int = withContext(io) {
        // Opened the way every background caller must (CLAUDE.md §7): the
        // nightly worker runs with no Activity alive, where
        // `requireDatabase()` throws -- and that throw would land in a
        // `runCatching` and come back as a clean success that did nothing,
        // which is BUG13 exactly.
        val database = session.openForBackgroundWork() ?: return@withContext 0
        val rollupDao = database.dailyRollupDao()

        database.withTransaction {
            var repaired = 0
            for (ledger in LedgerType.entries) {
                val before = rollupDao.allFor(ledger).associateBy(::keyOf)
                rollupDao.recomputeAll(ledger)
                val after = rollupDao.allFor(ledger).associateBy(::keyOf)
                repaired += differenceCount(before, after)
            }

            val meta = database.appMetaDao()
            meta.put(AppMetaEntity(AppMetaEntity.KEY_ROLLUP_RECONCILED_AT, clock.nowMillis().toString()))
            meta.put(AppMetaEntity(AppMetaEntity.KEY_ROLLUP_BUCKETS_REPAIRED, repaired.toString()))
            repaired
        }
    }

    /**
     * Buckets that changed, in either direction.
     *
     * A bucket that vanished and a bucket that appeared both count: the first
     * is a stale row for an entry that is no longer there, the second is a row
     * the incremental path never wrote. Counting only the survivors whose
     * figures moved would report zero for the two failure modes most likely to
     * come from a missed recompute.
     */
    private fun differenceCount(
        before: Map<String, DailyRollupEntity>,
        after: Map<String, DailyRollupEntity>,
    ): Int = (before.keys + after.keys).count { key -> before[key] != after[key] }

    private fun keyOf(row: DailyRollupEntity): String = listOf(
        row.localDate.toString(),
        row.ledger.name,
        row.categoryId,
        row.subcategoryId,
        row.merchantId,
        row.paymentMethodId,
    ).joinToString("|")
}
