package com.ledgerflow.core.domain.usecase

import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.domain.ingest.CaptureGapStore
import com.ledgerflow.core.domain.vault.VaultState
import javax.inject.Inject

/**
 * Background capture could not open the vault: decide whether that lost a
 * message, and if so, record it (SPEC.md §16 Q16).
 *
 * Called by the two capture entry points -- an SMS being written, a
 * notification's package being checked -- after the vault refused to open,
 * with the state it was left in. One call per message that arrived.
 *
 * **Before setup there is nothing to lose.** With no vault yet
 * ([VaultState.NeedsOnboarding]) the app is not capturing anything by design,
 * and a banner after onboarding saying messages were missed before it existed
 * would be noise. Every other state that leaves the vault shut -- a lost
 * Keystore wrap, a migration in progress or blocked, a restore interrupted --
 * means a message arrived and could not be kept, which is the case this exists
 * for. [VaultState.Unlocked] cannot reach here and is not recorded if it does.
 */
public class RecordCaptureGapUseCase @Inject constructor(
    private val store: CaptureGapStore,
    private val clock: Clock,
) {

    /** True when a gap was recorded, for the caller's log line. */
    public suspend operator fun invoke(stateAfterOpen: VaultState): Boolean {
        val lost = when (stateAfterOpen) {
            VaultState.NeedsOnboarding, VaultState.Unlocked -> false
            VaultState.Initializing,
            VaultState.Working,
            VaultState.RestoreInterrupted,
            is VaultState.NeedsRecovery,
            is VaultState.Upgrading,
            is VaultState.UpgradeBlocked,
            -> true
        }
        if (lost) store.recordMissed(clock.nowMillis())
        return lost
    }
}
