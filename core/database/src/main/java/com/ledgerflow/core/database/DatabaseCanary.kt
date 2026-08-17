package com.ledgerflow.core.database

import com.ledgerflow.core.database.entity.AppMetaEntity

/** Outcome of the canary check in the unlock flow (SPEC.md §7.3 step 1). */
public sealed interface CanaryResult {

    /** The row decrypts to the expected value. Proceed. */
    public data object Valid : CanaryResult

    /**
     * The database opened but the canary is wrong or missing.
     *
     * Route to the Recovery screen -- **never** wipe. This is what a DEK/database
     * mismatch after a restore looks like, and what a partially applied key
     * rotation (§7.7) would look like.
     */
    public data class Mismatch(val actual: String?) : CanaryResult
}

/**
 * The canary row (SPEC.md §7.3).
 *
 * SQLCipher already fails the *open* with an HMAC error on a wrong key, so this
 * is not the thing catching a wrong passphrase. What it does catch is a
 * database that opens fine but is not the one this DEK belongs to: a restored
 * file paired with the wrong key material, or a rotation that swapped one half
 * and not the other. Cheap defence-in-depth against a class of failure that is
 * otherwise silent.
 *
 * Open question 9 in SPEC.md §16 asks whether this is worth keeping; it is
 * implemented so that question can be answered with evidence rather than
 * speculation.
 */
public object DatabaseCanary {

    /** Written once at initialisation, alongside the schema version. */
    public suspend fun write(database: LedgerFlowDatabase) {
        database.appMetaDao().put(
            AppMetaEntity(AppMetaEntity.KEY_CANARY, AppMetaEntity.CANARY_VALUE),
        )
    }

    /** Verified on every unlock, after the database opens. */
    public suspend fun verify(database: LedgerFlowDatabase): CanaryResult {
        val actual = database.appMetaDao().value(AppMetaEntity.KEY_CANARY)
        return if (actual == AppMetaEntity.CANARY_VALUE) {
            CanaryResult.Valid
        } else {
            CanaryResult.Mismatch(actual)
        }
    }
}
