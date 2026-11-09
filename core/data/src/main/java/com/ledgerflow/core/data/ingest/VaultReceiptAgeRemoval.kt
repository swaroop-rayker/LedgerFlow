package com.ledgerflow.core.data.ingest

import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.domain.receipts.AgeRemovalRecord
import com.ledgerflow.core.domain.receipts.ReceiptAgeRemoval
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Removal by age's setting and record, in `app_meta` (ADR-0035 §6): beside
 * Drive's own status (ADR-0034, amended), because the Drive worker reads it and
 * opens the vault anyway. No schema change. It travels in a `.lfbk`, as Drive's
 * setting does.
 *
 * "Off" is stored as [OFF] rather than by deleting the key, since `app_meta`
 * has no delete; anything that is not one of the choices reads as off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
public class VaultReceiptAgeRemoval @Inject constructor(
    private val session: VaultSession,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ReceiptAgeRemoval {

    override fun observeAfterDays(): Flow<Int?> =
        session.whenUnlocked().flatMapLatest { database ->
            database?.appMetaDao()?.observeValue(KEY_AFTER_DAYS)?.map(::daysOf) ?: flowOf(null)
        }

    override suspend fun afterDays(): Int? = withContext(io) {
        daysOf(session.openForBackgroundWork()?.appMetaDao()?.value(KEY_AFTER_DAYS))
    }

    override suspend fun setAfterDays(days: Int?): Unit = withContext(io) {
        val value = days?.takeIf { it in ReceiptAgeRemoval.CHOICES }?.toString() ?: OFF
        session.openForBackgroundWork()?.appMetaDao()?.put(AppMetaEntity(KEY_AFTER_DAYS, value))
    }

    override fun observeLast(): Flow<AgeRemovalRecord?> =
        session.whenUnlocked().flatMapLatest { database ->
            database?.appMetaDao()?.observeValue(KEY_LAST)?.map(::recordOf) ?: flowOf(null)
        }

    override suspend fun record(record: AgeRemovalRecord): Unit = withContext(io) {
        session.openForBackgroundWork()?.appMetaDao()?.put(
            AppMetaEntity(KEY_LAST, "${record.at},${record.removed},${record.bytes}"),
        )
    }

    private fun daysOf(value: String?): Int? = value?.toIntOrNull()?.takeIf { it in ReceiptAgeRemoval.CHOICES }

    private fun recordOf(value: String?): AgeRemovalRecord? {
        val parts = value?.split(',')?.mapNotNull { it.toLongOrNull() } ?: return null
        if (parts.size != RECORD_PARTS) return null
        val (at, removed, bytes) = parts
        return AgeRemovalRecord(at = at, removed = removed.toInt(), bytes = bytes)
    }

    internal companion object {
        const val KEY_AFTER_DAYS = "receiptsRemoveAfterDays"
        const val KEY_LAST = "receiptsLastAgeRemoval"
        const val OFF = "off"
        private const val RECORD_PARTS = 3
    }
}
