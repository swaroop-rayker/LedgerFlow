package com.ledgerflow.core.data.taxonomy

import androidx.room.withTransaction
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.entity.PaymentMethodEntity
import com.ledgerflow.core.domain.taxonomy.NewPaymentMethod
import com.ledgerflow.core.domain.taxonomy.PaymentMethodRepository
import com.ledgerflow.core.domain.taxonomy.TaxonomyError
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.PaymentMethod
import com.ledgerflow.core.model.PaymentMethodType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Payment methods over Room (SPEC.md §5.5). */
@Singleton
public class DefaultPaymentMethodRepository @Inject constructor(
    private val session: VaultSession,
    private val ids: Uuid7Generator,
    private val clock: Clock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : PaymentMethodRepository {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeAll(): Flow<List<PaymentMethod>> =
        session.whenUnlocked().flatMapLatest { database ->
            database?.paymentMethodDao()?.observeLive()?.map { rows -> rows.map { it.toDomain() } }
                ?: flowOf(emptyList())
        }

    override suspend fun find(id: String): PaymentMethod? = withContext(io) {
        session.requireDatabase().paymentMethodDao().byId(id)
            ?.takeIf { it.deletedAt == 0L }
            ?.toDomain()
    }

    override suspend fun create(request: NewPaymentMethod): TaxonomyResult<PaymentMethod> =
        withContext(io) {
            val label = request.label.trim()
            if (label.isEmpty()) {
                return@withContext TaxonomyResult.Failure(TaxonomyError.BlankName)
            }

            val dao = session.requireDatabase().paymentMethodDao()
            if (dao.findLiveByLabel(label) != null) {
                return@withContext TaxonomyResult.Failure(TaxonomyError.DuplicateName(label))
            }

            val entity = PaymentMethodEntity(
                id = ids.generate(),
                type = request.type,
                label = label,
                issuer = request.issuer?.trim()?.ifEmpty { null },
                // Only the last four digits are ever stored (§5.5). Anything
                // longer arriving here is a full card number, and truncating is
                // the only acceptable outcome.
                last4 = request.last4?.trim()?.takeLast(LAST4_LENGTH)?.ifEmpty { null },
                colorArgb = request.colorArgb,
                isDefault = false,
            )
            dao.insert(entity)
            // Set after insert so the "exactly one default" transaction owns it.
            if (request.isDefault || dao.count() == 1) dao.setDefault(entity.id)
            TaxonomyResult.Success(entity.copy(isDefault = request.isDefault).toDomain())
        }

    override suspend fun update(method: PaymentMethod): TaxonomyResult<Unit> = withContext(io) {
        val label = method.label.trim()
        if (label.isEmpty()) return@withContext TaxonomyResult.Failure(TaxonomyError.BlankName)

        val dao = session.requireDatabase().paymentMethodDao()
        val existing = dao.byId(method.id)
            ?: return@withContext TaxonomyResult.Failure(TaxonomyError.NotFound)

        val clash = dao.findLiveByLabel(label)
        if (clash != null && clash != method.id) {
            return@withContext TaxonomyResult.Failure(TaxonomyError.DuplicateName(label))
        }
        dao.update(
            existing.copy(
                type = method.type,
                label = label,
                issuer = method.issuer,
                last4 = method.last4?.takeLast(LAST4_LENGTH),
                colorArgb = method.colorArgb,
            ),
        )
        TaxonomyResult.Success(Unit)
    }

    override suspend fun setDefault(id: String): TaxonomyResult<Unit> = withContext(io) {
        val dao = session.requireDatabase().paymentMethodDao()
        dao.byId(id)?.takeIf { it.deletedAt == 0L }
            ?: return@withContext TaxonomyResult.Failure(TaxonomyError.NotFound)
        dao.setDefault(id)
        TaxonomyResult.Success(Unit)
    }

    override suspend fun delete(id: String): TaxonomyResult<Unit> = withContext(io) {
        val database = session.requireDatabase()
        val dao = database.paymentMethodDao()
        val entries = database.ledgerEntryDao()

        dao.byId(id) ?: return@withContext TaxonomyResult.Failure(TaxonomyError.NotFound)

        database.withTransaction {
            // Unlike a merchant, a payment method is a live FK the entry form
            // offers. Past entries keep their history by losing the reference
            // rather than pointing at a row the picker no longer shows.
            LedgerType.entries.forEach { ledger ->
                entries.clearPaymentMethod(ledger, id, clock.nowMillis())
            }
            dao.softDelete(id, clock.nowMillis())
        }
        TaxonomyResult.Success(Unit)
    }

    override suspend fun seedSystemDefaults(): Int = withContext(io) {
        val dao = session.requireDatabase().paymentMethodDao()
        if (dao.count() > 0) return@withContext 0

        val cash = PaymentMethodEntity(
            id = ids.generate(),
            type = PaymentMethodType.CASH,
            label = DefaultTaxonomy.DEFAULT_PAYMENT_METHOD_LABEL,
            issuer = null,
            last4 = null,
            colorArgb = null,
            isDefault = true,
        )
        dao.insert(cash)
        1
    }

    private companion object {
        private const val LAST4_LENGTH = 4
    }
}
