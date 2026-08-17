package com.ledgerflow.core.domain.vault

import kotlinx.coroutines.flow.StateFlow

/**
 * The vault: the DEK, the open database, and the transitions between locked and
 * unlocked (SPEC.md §7.3).
 *
 * The port carries no crypto and no Android types. That is not decoration --
 * `:core:domain` depends on `:core:model` and `:core:common` only (CLAUDE.md
 * §3), so `UnlockFailure`, `Dek` and `LedgerFlowDatabase` all stop at the
 * implementation in `:core:data` and are mapped to the vocabulary above.
 */
public interface VaultRepository {

    /** The single source of truth for what the app shell should be showing. */
    public val state: StateFlow<VaultState>

    /**
     * Decides the starting state and, if a Keystore wrap exists, attempts the
     * silent unlock (§7.3 step 1). Safe to call more than once; a call while
     * already [VaultState.Unlocked] is a no-op rather than a re-open.
     */
    public suspend fun openOnLaunch()

    /**
     * First run (§7.4): generate the DEK, wrap it under both factors, create the
     * database, write the canary and the chosen base currency.
     *
     * Called only once the whole onboarding gate has been satisfied. Nothing is
     * written to disk before that point, so an app killed mid-onboarding starts
     * the gate again rather than leaving a half-initialised vault that the next
     * launch would treat as complete and walk straight past.
     */
    public suspend fun initialize(request: VaultInitRequest): VaultOutcome

    /**
     * §7.3 step 2: recover with the 24 words, then regenerate the Keystore wrap
     * so the next launch is silent again.
     */
    public suspend fun unlockWithPhrase(mnemonic: List<String>): VaultOutcome
}

/** Everything the §7.4 gate collected, handed over in one piece. */
public data class VaultInitRequest(
    val mnemonic: List<String>,
    val baseCurrency: String,
    /** Persisted SAF tree for nightly backups, or null if the user declined. */
    val backupTreeUri: String? = null,
)

/** The result of an unlock attempt, from the caller's point of view. */
public sealed interface VaultOutcome {

    public data object Unlocked : VaultOutcome

    /** The phrase is not a well-formed BIP-39 mnemonic. Reported before any KDF ran. */
    public data class PhraseRejected(val validation: PhraseValidation) : VaultOutcome

    /**
     * A well-formed phrase that does not open this vault. Distinct from
     * [PhraseRejected] because the remedy is different: not "check for a typo"
     * but "this is a phrase for some other install".
     */
    public data object PhraseDidNotMatch : VaultOutcome

    /** Everything else, carrying the reason the Recovery screen should explain. */
    public data class Failed(val reason: RecoveryReason) : VaultOutcome
}
