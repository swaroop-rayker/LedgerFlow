package com.ledgerflow.core.data.vault

import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.data.di.VaultDatabaseName
import com.ledgerflow.core.crypto.Dek
import com.ledgerflow.core.crypto.DekManager
import com.ledgerflow.core.crypto.UnlockFailure
import com.ledgerflow.core.crypto.UnlockResult
import com.ledgerflow.core.database.CanaryResult
import com.ledgerflow.core.database.DatabaseCanary
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.database.LedgerFlowDatabaseFactory
import com.ledgerflow.core.database.WalCheckpointObserver
import com.ledgerflow.core.database.migration.MigrationAssessment
import com.ledgerflow.core.database.migration.PreMigrationGuard
import com.ledgerflow.core.database.migration.SnapshotResult
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.domain.vault.PhraseValidation
import com.ledgerflow.core.domain.vault.RecoveryPhraseValidator
import com.ledgerflow.core.domain.vault.RecoveryReason
import com.ledgerflow.core.domain.vault.UpgradeBlockReason
import com.ledgerflow.core.domain.vault.VaultInitRequest
import com.ledgerflow.core.domain.vault.VaultOutcome
import com.ledgerflow.core.domain.vault.VaultRepository
import com.ledgerflow.core.domain.vault.VaultState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
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
    /**
     * Which file the vault lives in.
     *
     * Injected rather than fixed so instrumented tests can open their **own**
     * database. They previously shared the real one and deleted it in teardown,
     * which meant every `connectedAndroidTest` run destroyed the debug
     * install's ledger -- squarely against CLAUDE.md §8's BUG1(e), and it ate a
     * real vault twice before it was fixed. A name that cannot be varied is a
     * class that cannot be tested without collateral damage.
     */
    @param:VaultDatabaseName private val databaseName: String = LedgerFlowDatabase.DATABASE_NAME,
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

    /**
     * The open database, opening it first if nothing else has (SPEC.md §5.1).
     *
     * **This is what makes background capture work at all.** Everything that
     * unlocks the vault used to run from `AppViewModel`, which exists only while
     * the UI does — so an SMS arriving with the app closed reached a receiver
     * whose `requireDatabase()` threw, and the message was logged and dropped.
     * §5.1 says a financial SMS is never silently dropped, and for the whole of
     * P2 it was, on every message that arrived while the user was not looking.
     * Found on the owner's device: a live UPI payment vanished while three
     * credits that landed minutes later, with the app open, were captured.
     *
     * **A headless unlock is what the key hierarchy was designed for.** §7
     * forbids `setUserAuthenticationRequired(true)` on the DEK-wrapping key
     * precisely so the Keystore unwrap needs no user present; background capture
     * is the reason that rule exists. This adds no wrap and no key material — it
     * calls the same [openOnLaunch] the UI does, which is mutex-guarded and
     * returns immediately when the database is already open.
     *
     * Null means the vault genuinely cannot be opened: onboarding was never
     * completed, or the Keystore wrap is gone and §7.3 wants the Recovery
     * screen. The caller must refuse honestly rather than pretend — it must
     * never wipe, and it must never treat this as "no such message".
     */
    internal suspend fun openForBackgroundWork(): LedgerFlowDatabase? {
        // Checked outside the lock deliberately: the common case is an open
        // vault and a burst of messages, and taking the mutex per SMS would
        // serialise a receiver that has ten seconds to live (CLAUDE.md §7).
        // Losing the race costs one no-op call into `openOnLaunch`.
        database?.let { return it }
        openOnLaunch()
        return database
    }

    /**
     * The database as it comes and goes, for repositories exposing cold `Flow`s.
     *
     * Emits null while locked rather than throwing, so a screen observing
     * categories during a recovery does not crash -- it simply sees nothing until
     * the vault opens, and then rebinds. A repository that captured a DAO at
     * construction could not do that, because the DAO does not exist yet when
     * Hilt builds the graph.
     */
    internal fun whenUnlocked(): Flow<LedgerFlowDatabase?> =
        state.map { if (it is VaultState.Unlocked) database else null }
            .distinctUntilChanged()

    /**
     * Closes the database and returns to the pre-launch state.
     *
     * Deliberately **not** on [VaultRepository]: the domain layer has no
     * business ending a database's life. This class does, because it is the
     * thing that owns that life -- and something that owns a lifetime and
     * cannot end it leaks by construction. Every open vault holds a native
     * SQLCipher connection pool.
     *
     * Not a wipe and not a lock: nothing on disk changes, and [openOnLaunch]
     * reopens. The session is reusable afterwards, which is why the state goes
     * back to [VaultState.Initializing] rather than staying `Unlocked` over a
     * handle that no longer exists.
     */
    public suspend fun close() {
        mutex.withLock {
            withContext(io) { database?.close() }
            database = null
            _state.value = VaultState.Initializing
        }
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
        // BUG8(d), ADR-0019. All of this happens *before* Room is asked to open
        // the file, because asking Room is what runs the migration.
        val migratingFrom = when (val prepared = prepareForMigration(dek)) {
            is MigrationPreparation.Blocked ->
                return@withContext VaultOutcome.UpgradeBlocked(prepared.reason)
            is MigrationPreparation.Proceed -> prepared.migratingFrom
        }

        val opened = runCatching { LedgerFlowDatabaseFactory.create(context, dek, databaseName) }
            .getOrElse { return@withContext failedOpen(migratingFrom) }

        val result = runCatching {
            // Room opens lazily, so the migration has not run yet. Touching the
            // database forces it here, where a failure is still attributable to
            // the upgrade and the snapshot is still the right answer.
            opened.openHelper.writableDatabase
            if (seedMetadata != null) {
                DatabaseCanary.write(opened)
                opened.appMetaDao().putAll(initialMetadata(seedMetadata))
            }
            DatabaseCanary.verify(opened)
        }.getOrElse {
            opened.close()
            return@withContext failedOpen(migratingFrom)
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
     * An open that threw, attributed correctly.
     *
     * If a migration was pending, this is a failed migration and the snapshot
     * goes back — the app's only automatic restore (§8.1). If not, the file
     * simply would not open, which is Recovery's business (§7.3). Reporting the
     * second as the first would put an upgrade screen in front of a user whose
     * remedy is their twenty-four words.
     */
    private fun failedOpen(migratingFrom: Int?): VaultOutcome {
        if (migratingFrom == null) return VaultOutcome.Failed(RecoveryReason.DatabaseUnopenable)
        val restored = guard().restore(migratingFrom)
        return VaultOutcome.UpgradeBlocked(UpgradeBlockReason.MigrationFailed(restored))
    }

    /** What [prepareForMigration] decided. */
    private sealed interface MigrationPreparation {
        /**
         * Safe to open. [migratingFrom] is the version a snapshot was taken at,
         * or null when nothing was pending — which is also what tells a failed
         * open whether there is anything to restore.
         */
        data class Proceed(val migratingFrom: Int?) : MigrationPreparation
        data class Blocked(val reason: UpgradeBlockReason) : MigrationPreparation
    }

    private fun guard(): PreMigrationGuard = PreMigrationGuard(
        databaseFile = context.getDatabasePath(databaseName),
        // filesDir, never cacheDir: the OS may clear cacheDir between taking the
        // snapshot and needing it back (Law 5).
        snapshotDir = File(context.filesDir, SNAPSHOT_DIR),
    )

    /**
     * Takes the rollback point, or explains why the upgrade cannot start
     * (SPEC.md §8.1, ADR-0019).
     *
     * The snapshot is a copy of the encrypted database file rather than a
     * `.lfbk`: a `.lfbk` is phrase-derived (ADR-0011) and this path holds a DEK
     * and no phrase. ADR-0019 has the reasoning and the amendment to §8.1.
     *
     * [MigrationAssessment.Unreadable] deliberately proceeds rather than
     * blocking. It means the file will not open under this key at all, which is
     * §7.3's problem and not an upgrade's — and the open below will produce the
     * Recovery routing that actually helps.
     */
    private fun prepareForMigration(dek: Dek): MigrationPreparation {
        val guard = guard()
        return when (val assessment = guard.assess(dek.bytes())) {
            MigrationAssessment.NotNeeded -> {
                // A clean launch at the current version has now happened, so a
                // snapshot from the previous one has done its job (§8.1).
                guard.discardStaleSnapshot()
                MigrationPreparation.Proceed(migratingFrom = null)
            }

            is MigrationAssessment.Unreadable -> MigrationPreparation.Proceed(migratingFrom = null)

            is MigrationAssessment.Downgrade -> MigrationPreparation.Blocked(
                UpgradeBlockReason.Downgrade(assessment.onDisk, assessment.supported),
            )

            is MigrationAssessment.Required -> {
                _state.value = VaultState.Upgrading(assessment.from, assessment.to)
                when (val snapshot = guard.takeSnapshot(dek.bytes(), assessment.from)) {
                    is SnapshotResult.Success -> MigrationPreparation.Proceed(assessment.from)
                    is SnapshotResult.InsufficientStorage -> MigrationPreparation.Blocked(
                        UpgradeBlockReason.InsufficientStorage(
                            snapshot.requiredBytes,
                            snapshot.availableBytes,
                        ),
                    )
                    is SnapshotResult.Failure ->
                        MigrationPreparation.Blocked(UpgradeBlockReason.SnapshotFailed)
                }
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
        is VaultOutcome.UpgradeBlocked -> VaultState.UpgradeBlocked(reason)
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

        /**
         * Where a pre-migration snapshot lives, under `filesDir` (Law 5).
         *
         * Not `cacheDir`: the OS may clear it between the snapshot being taken
         * and the migration failing, which would remove the rollback point at
         * exactly the moment it is needed.
         */
        private const val SNAPSHOT_DIR: String = "premigration"
    }
}
