package com.ledgerflow.core.data.vault

import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.crypto.Dek
import com.ledgerflow.core.crypto.DekManager
import com.ledgerflow.core.crypto.UnlockFailure
import com.ledgerflow.core.crypto.UnlockResult
import com.ledgerflow.core.database.CanaryResult
import com.ledgerflow.core.database.DatabaseCanary
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.database.LedgerFlowDatabaseFactory
import com.ledgerflow.core.database.WalCheckpointObserver
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.domain.vault.PhraseValidation
import com.ledgerflow.core.domain.vault.RecoveryPhraseValidator
import com.ledgerflow.core.domain.vault.RecoveryReason
import com.ledgerflow.core.domain.vault.VaultInitRequest
import com.ledgerflow.core.domain.vault.VaultOutcome
import com.ledgerflow.core.domain.vault.VaultRepository
import com.ledgerflow.core.domain.vault.VaultState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The unlock flow (SPEC.md §7.3), wired end to end for the first time.
 *
 * Phase 0 built the parts -- `DekManager`, `LedgerFlowDatabaseFactory`,
 * `DatabaseCanary` -- and nothing called them. This is the caller.
 *
 * It is both the [VaultRepository] the domain layer sees and the holder of the
 * open [LedgerFlowDatabase] its sibling repositories need. Those are two views
 * of one responsibility (owning the vault's lifetime), not two responsibilities,
 * so they live in one class rather than a repository that forwards every call to
 * a session object.
 *
 * **There is no wipe path here and there must never be one** (CLAUDE.md §7).
 * Every failure below produces a [VaultState.NeedsRecovery] for the user to act
 * on. The only destruction in LedgerFlow is behind the explicit type-DELETE
 * dialog in §7.3 step 3, which is a screen and a user's choice.
 */
@Singleton
public class VaultSession @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dekManager: DekManager,
    private val validator: RecoveryPhraseValidator,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : VaultRepository {

    private val _state = MutableStateFlow<VaultState>(VaultState.Initializing)
    override val state: StateFlow<VaultState> = _state.asStateFlow()

    /**
     * Serialises every transition.
     *
     * Without it, a Recovery screen submit racing the launch-time Keystore
     * attempt can open the database twice and leave one handle orphaned --
     * which in WAL mode means a connection nobody checkpoints.
     */
    private val mutex = Mutex()

    @Volatile
    private var database: LedgerFlowDatabase? = null

    /**
     * The open database, for repositories in this module.
     *
     * `internal` deliberately: a feature module reaching for a DAO would route
     * around the repository layer, and around `ApproveTransactionUseCase` with
     * it (Law 1).
     */
    internal fun requireDatabase(): LedgerFlowDatabase = requireNotNull(database) {
        "Vault is locked; no database is open. Callers must observe VaultState first."
    }

    override suspend fun openOnLaunch() {
        mutex.withLock {
            // Pull the BIP-39 wordlist off the APK here, on IO, so that every
            // later call -- a Recovery keystroke, a phrase validation -- is pure
            // memory. Without this the first touch is on the main thread and
            // StrictMode kills the debug build, which is how this was found.
            withContext(io) { validator.warmUp() }

            if (database != null) {
                _state.value = VaultState.Unlocked
                return
            }
            if (!withContext(io) { dekManager.isInitialized() }) {
                // No phrase wrap on disk: the §7.4 gate was never completed.
                // Note this is the *phrase* wrap, not the Keystore one -- the
                // phrase is the mandatory factor, so its absence is the only
                // honest definition of "not set up".
                _state.value = VaultState.NeedsOnboarding
                return
            }

            _state.value = VaultState.Working
            _state.value = when (val result = withContext(io) { dekManager.unlockWithKeystore() }) {
                is UnlockResult.Success -> openDatabase(result.dek).toState()
                is UnlockResult.Failure -> VaultState.NeedsRecovery(result.reason.toRecoveryReason())
            }
        }
    }

    override suspend fun initialize(request: VaultInitRequest): VaultOutcome = mutex.withLock {
        val validation = validator.validate(request.mnemonic)
        if (validation !is PhraseValidation.Valid) {
            return@withLock VaultOutcome.PhraseRejected(validation)
        }

        _state.value = VaultState.Working
        val dek = when (val result = withContext(io) { dekManager.initialize(request.mnemonic) }) {
            is UnlockResult.Success -> result.dek
            is UnlockResult.Failure -> {
                val reason = result.reason.toRecoveryReason()
                _state.value = VaultState.NeedsRecovery(reason)
                return@withLock VaultOutcome.Failed(reason)
            }
        }

        val opened = openDatabase(dek, seedMetadata = request)
        _state.value = opened.toState()
        opened
    }

    override suspend fun unlockWithPhrase(mnemonic: List<String>): VaultOutcome = mutex.withLock {
        if (database != null) return@withLock VaultOutcome.Unlocked

        val validation = validator.validate(mnemonic)
        if (validation !is PhraseValidation.Valid) {
            // Reported without touching the KDF (CLAUDE.md §7). The state is
            // left on the Recovery screen -- a rejected phrase is not a new
            // failure, it is the same one, still unresolved.
            return@withLock VaultOutcome.PhraseRejected(validation)
        }

        val previous = _state.value
        _state.value = VaultState.Working

        // Expensive: PBKDF2 (2048 x HMAC-SHA512) then HKDF then AES-GCM. Off the
        // main thread or StrictMode kills the process in debug, which is the
        // system working.
        val dek = when (val result = withContext(io) { dekManager.unlockWithPhrase(mnemonic) }) {
            is UnlockResult.Success -> result.dek
            is UnlockResult.Failure -> {
                _state.value = previous
                return@withLock result.reason.toPhraseOutcome()
            }
        }

        // DekManager re-wrapped KEK-A on the way through, so the next launch is
        // silent again. "User loses nothing" includes not typing 24 words twice.
        val opened = openDatabase(dek)
        _state.value = opened.toState()
        opened
    }

    /**
     * Opens the database under [dek] and verifies the canary.
     *
     * A canary mismatch does **not** close and discard the database quietly, and
     * it certainly does not delete it: the rows are probably fine and simply
     * belong to a different key (D-08). The handle is released so nothing reads
     * through it, and the user is sent to Recovery.
     */
    private suspend fun openDatabase(
        dek: Dek,
        seedMetadata: VaultInitRequest? = null,
    ): VaultOutcome = withContext(io) {
        val opened = runCatching { LedgerFlowDatabaseFactory.create(context, dek) }
            .getOrElse { return@withContext VaultOutcome.Failed(RecoveryReason.DatabaseUnopenable) }

        val result = runCatching {
            if (seedMetadata != null) {
                DatabaseCanary.write(opened)
                opened.appMetaDao().putAll(initialMetadata(seedMetadata))
            }
            DatabaseCanary.verify(opened)
        }.getOrElse {
            opened.close()
            return@withContext VaultOutcome.Failed(RecoveryReason.DatabaseUnopenable)
        }

        when (result) {
            is CanaryResult.Mismatch -> {
                opened.close()
                VaultOutcome.Failed(RecoveryReason.CanaryMismatch)
            }

            CanaryResult.Valid -> {
                database = opened
                registerWalCheckpoint(opened)
                dek.destroy()
                VaultOutcome.Unlocked
            }
        }
    }

    /**
     * BUG2's second countermeasure, finally attached to a real database.
     *
     * `ProcessLifecycleOwner` observers must be added on the main thread, and
     * this runs on IO -- hence the explicit hop rather than a bare `addObserver`.
     */
    private suspend fun registerWalCheckpoint(opened: LedgerFlowDatabase) {
        val observer = WalCheckpointObserver(opened)
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        }
    }

    private fun initialMetadata(request: VaultInitRequest): List<AppMetaEntity> = buildList {
        add(AppMetaEntity(AppMetaEntity.KEY_BASE_CURRENCY, request.baseCurrency))
        add(
            AppMetaEntity(
                AppMetaEntity.KEY_SCHEMA_VERSION,
                LedgerFlowDatabase.VERSION.toString(),
            ),
        )
        // Two wraps, and that is now permanent (ADR-0011). The counter still
        // exists because §7.7 rotation bumps it, not because a third is coming.
        add(AppMetaEntity(AppMetaEntity.KEY_DEK_WRAP_VERSION, DEK_WRAP_VERSION))
        request.backupTreeUri?.let { add(AppMetaEntity(KEY_BACKUP_TREE_URI, it)) }
    }

    private fun VaultOutcome.toState(): VaultState = when (this) {
        VaultOutcome.Unlocked -> VaultState.Unlocked
        is VaultOutcome.Failed -> VaultState.NeedsRecovery(reason)
        is VaultOutcome.PhraseRejected -> VaultState.NeedsRecovery(RecoveryReason.KeystoreUnavailable)
        VaultOutcome.PhraseDidNotMatch -> VaultState.NeedsRecovery(RecoveryReason.KeystoreUnavailable)
    }

    /**
     * Crypto vocabulary in, domain vocabulary out.
     *
     * Every branch lands somewhere recoverable -- that is the point of doing the
     * mapping explicitly rather than passing the crypto type upward.
     */
    private fun UnlockFailure.toRecoveryReason(): RecoveryReason = when (this) {
        UnlockFailure.KeystoreUnavailable -> RecoveryReason.KeystoreUnavailable
        UnlockFailure.NotInitialized -> RecoveryReason.KeystoreWrapMissing
        UnlockFailure.AuthenticationFailed -> RecoveryReason.KeystoreWrapDamaged
        is UnlockFailure.MalformedBlob -> RecoveryReason.KeystoreWrapDamaged
        is UnlockFailure.UnsupportedFormat -> RecoveryReason.KeystoreWrapDamaged
        is UnlockFailure.InvalidMnemonic -> RecoveryReason.KeystoreWrapDamaged
    }

    /** The same failures, seen from the phrase path, where they mean something else. */
    private fun UnlockFailure.toPhraseOutcome(): VaultOutcome = when (this) {
        // A well-formed phrase whose GCM tag did not verify: right format,
        // wrong vault. Not a typo -- validate() already ruled that out.
        UnlockFailure.AuthenticationFailed -> VaultOutcome.PhraseDidNotMatch
        is UnlockFailure.InvalidMnemonic -> VaultOutcome.PhraseRejected(PhraseValidation.ChecksumMismatch)
        UnlockFailure.NotInitialized -> VaultOutcome.Failed(RecoveryReason.KeystoreWrapMissing)
        else -> VaultOutcome.Failed(RecoveryReason.KeystoreWrapDamaged)
    }

    public companion object {
        /** SAF tree the nightly backup writes to. Null until the user grants one. */
        public const val KEY_BACKUP_TREE_URI: String = "backupTreeUri"

        /** KEK-A + KEK-B. ADR-0011 settled that there is no third. */
        public const val DEK_WRAP_VERSION: String = "2"
    }
}
