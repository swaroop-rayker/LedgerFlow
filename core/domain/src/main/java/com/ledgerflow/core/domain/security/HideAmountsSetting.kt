package com.ledgerflow.core.domain.security

import kotlinx.coroutines.flow.Flow

/**
 * Settings > Security & privacy > Hide amounts on Home (V2-PLAN §3.5).
 *
 * **Kept in the vault** (`app_meta`), not beside the app lock's switches in
 * ADR-0030's device file (owner, 2026-10-31). Nothing needs it before unlock --
 * Home's figures come from the vault too -- so ADR-0030's second test sends it
 * here, and a backup carries it: a user who hid their amounts finds them
 * hidden after a restore.
 */
public interface HideAmountsSetting {

    /** Whether Home hides its amounts. Emits once the vault is open, and nothing while it is closed. */
    public val enabled: Flow<Boolean>

    public suspend fun set(enabled: Boolean)
}
