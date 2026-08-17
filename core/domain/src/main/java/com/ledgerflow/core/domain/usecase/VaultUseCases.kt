package com.ledgerflow.core.domain.usecase

import com.ledgerflow.core.domain.vault.PhraseValidation
import com.ledgerflow.core.domain.vault.RecoveryPhraseValidator
import com.ledgerflow.core.domain.vault.VaultInitRequest
import com.ledgerflow.core.domain.vault.VaultOutcome
import com.ledgerflow.core.domain.vault.VaultRepository
import com.ledgerflow.core.domain.vault.VaultState
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * §7.3 step 1, at launch: try the Keystore, decide what the shell shows.
 *
 * Thin over the repository on purpose. It exists so the shell depends on a name
 * that says what happens rather than on the vault's whole surface -- the shell
 * has no business being able to call [InitializeVaultUseCase].
 */
public class OpenVaultOnLaunchUseCase @Inject constructor(
    private val vault: VaultRepository,
) {
    public suspend operator fun invoke(): Unit = vault.openOnLaunch()
}

/**
 * The vault state, for the app shell to route on.
 *
 * A forwarding class, and deliberately so: it is what lets the shell observe the
 * vault without holding a [VaultRepository] and therefore without being able to
 * call [InitializeVaultUseCase] or [RecoverVaultUseCase]. The restriction is the
 * point.
 */
public class ObserveVaultStateUseCase @Inject constructor(
    private val vault: VaultRepository,
) {
    public operator fun invoke(): StateFlow<VaultState> = vault.state
}

/** First run (SPEC.md §7.4), once the entire gate has been satisfied. */
public class InitializeVaultUseCase @Inject constructor(
    private val vault: VaultRepository,
) {
    public suspend operator fun invoke(request: VaultInitRequest): VaultOutcome =
        vault.initialize(request)
}

/**
 * §7.3 step 2: the 24 words.
 *
 * Validates before delegating, so a phrase that cannot possibly be right never
 * reaches the KDF. The repository validates again -- this is a UX guard, not the
 * security boundary, and a use case that can be bypassed is not a place to put
 * the only check.
 */
public class RecoverVaultUseCase @Inject constructor(
    private val vault: VaultRepository,
    private val validator: RecoveryPhraseValidator,
) {
    public suspend operator fun invoke(mnemonic: List<String>): VaultOutcome {
        val validation = validator.validate(mnemonic)
        if (validation !is PhraseValidation.Valid) {
            return VaultOutcome.PhraseRejected(validation)
        }
        return vault.unlockWithPhrase(mnemonic)
    }
}

// There is deliberately no ValidateRecoveryPhraseUseCase wrapping
// RecoveryPhraseValidator. That port is a pure, synchronous domain service with
// no state and no side effects -- wrapping each of `validate`, `suggestions`,
// `isKnownWord` and `parse` in its own single-method class would add four types
// that forward one call each and hide nothing. ViewModels inject the validator
// directly; use cases exist here for the operations that *do* something.
