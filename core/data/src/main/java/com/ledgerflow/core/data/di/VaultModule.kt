package com.ledgerflow.core.data.di

import android.content.Context
import com.ledgerflow.core.crypto.DekManager
import com.ledgerflow.core.crypto.FileWrappedDekStore
import com.ledgerflow.core.crypto.WrappedDekStore
import com.ledgerflow.core.crypto.keystore.AndroidKeystoreKek
import com.ledgerflow.core.crypto.keystore.KeystoreKek
import com.ledgerflow.core.data.export.DefaultExportRepository
import com.ledgerflow.core.data.ledger.DefaultDraftRepository
import com.ledgerflow.core.data.ledger.DefaultLedgerRepository
import com.ledgerflow.core.data.taxonomy.DefaultCategoryRepository
import com.ledgerflow.core.data.taxonomy.DefaultMerchantRepository
import com.ledgerflow.core.data.taxonomy.DefaultPaymentMethodRepository
import com.ledgerflow.core.data.vault.Bip39PhraseValidator
import com.ledgerflow.core.data.vault.DefaultStorageMaintenance
import com.ledgerflow.core.data.vault.RecoveryKitWriter
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.domain.export.ExportRepository
import com.ledgerflow.core.domain.ledger.DraftRepository
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.taxonomy.CategoryRepository
import com.ledgerflow.core.domain.taxonomy.MerchantRepository
import com.ledgerflow.core.domain.taxonomy.PaymentMethodRepository
import com.ledgerflow.core.domain.vault.RecoveryKitRepository
import com.ledgerflow.core.domain.vault.RecoveryPhraseValidator
import com.ledgerflow.core.domain.vault.StorageMaintenance
import com.ledgerflow.core.domain.vault.VaultRepository
import com.ledgerflow.core.data.ingest.DefaultRawIngestRepository
import com.ledgerflow.core.domain.ingest.RawIngestRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.security.SecureRandom
import javax.inject.Singleton

/** Constructs `:core:crypto`, which has no DI of its own by design. */
@Module
@InstallIn(SingletonComponent::class)
public object CryptoModule {

    /**
     * Wrapped-DEK blobs live in `filesDir/keys` (Law 5).
     *
     * Not `cacheDir`: the OS reclaims it, and a reclaimed
     * `wrapped_dek_phrase.bin` is a vault that only the 24 words can reopen --
     * for a user who has done nothing wrong.
     */
    @Provides
    @Singleton
    public fun wrappedDekStore(@ApplicationContext context: Context): WrappedDekStore =
        // The lambda matters: `context.filesDir` stats the data directory, and
        // this provider runs during graph construction on the main thread.
        FileWrappedDekStore { File(context.filesDir, KEY_DIRECTORY) }

    @Provides
    @Singleton
    public fun keystoreKek(): KeystoreKek = AndroidKeystoreKek()

    @Provides
    @Singleton
    public fun dekManager(
        store: WrappedDekStore,
        keystoreKek: KeystoreKek,
        random: SecureRandom,
    ): DekManager = DekManager(store, keystoreKek, random)

    private const val KEY_DIRECTORY = "keys"
}

/** Binds the domain ports to their `:core:data` implementations. */
@Module
@InstallIn(SingletonComponent::class)
public interface VaultModule {

    @Binds
    public fun vaultRepository(impl: VaultSession): VaultRepository

    @Binds
    public fun recoveryPhraseValidator(impl: Bip39PhraseValidator): RecoveryPhraseValidator

    @Binds
    public fun recoveryKitRepository(impl: RecoveryKitWriter): RecoveryKitRepository
}

/**
 * The ledger write path and in-flight form state (SPEC.md §6.1, §6.1.2).
 *
 * Binding `LedgerRepository` here does not weaken Law 1: the interface is
 * injectable, and `LedgerSingleWriterTest` is what makes
 * `ApproveTransactionUseCase` the only caller of `approve`. Hiding the binding
 * would not help -- Hilt has to see it to build the use case -- so the
 * enforcement lives where it can actually fail a build.
 */
@Module
@InstallIn(SingletonComponent::class)
public interface LedgerModule {

    @Binds
    public fun ledgerRepository(impl: DefaultLedgerRepository): LedgerRepository

    @Binds
    public fun draftRepository(impl: DefaultDraftRepository): DraftRepository

    /**
     * Compaction is bound beside the ledger rather than under it.
     *
     * It used to be a `LedgerRepository` method, on that interface's own stated
     * condition: "give it its own port when something else needs it". A
     * taxonomy purge erases a name the user typed and has the same obligation
     * to make the bytes go, so ADR-0016 is that something else.
     */
    @Binds
    public fun storageMaintenance(impl: DefaultStorageMaintenance): StorageMaintenance
}

/**
 * Writing the ledger out to files the user owns (SPEC.md §5.9).
 *
 * Its own module rather than a binding on [LedgerModule], because an export is
 * not a ledger operation: it reads every table, writes nothing, and the thing it
 * produces is unencrypted and leaves the device. Keeping it separate is how the
 * graph says that out loud.
 */
@Module
@InstallIn(SingletonComponent::class)
public interface ExportModule {

    @Binds
    public fun exportRepository(impl: DefaultExportRepository): ExportRepository
}

/** Categories, merchants and payment methods (SPEC.md §5.5). */
@Module
@InstallIn(SingletonComponent::class)
public interface TaxonomyModule {

    @Binds
    public fun categoryRepository(impl: DefaultCategoryRepository): CategoryRepository

    @Binds
    public fun merchantRepository(impl: DefaultMerchantRepository): MerchantRepository

    @Binds
    public fun paymentMethodRepository(impl: DefaultPaymentMethodRepository): PaymentMethodRepository
}

/**
 * Raw ingest capture (SPEC.md §5.1, §5.2). Schema v6.
 *
 * Bound here, in `:core:data`, so `:feature:ingest` sees only the port. That is
 * the usual module rule, and here it also keeps the privacy guarantee legible:
 * the capture adapters can ask "is this package allowed" and cannot reach a
 * table to answer it themselves.
 */
@Module
@InstallIn(SingletonComponent::class)
public interface IngestModule {

    @Binds
    public fun rawIngestRepository(impl: DefaultRawIngestRepository): RawIngestRepository
}
