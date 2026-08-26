package com.ledgerflow.core.domain.vault

/**
 * Where the app is in the unlock flow (SPEC.md §7.3).
 *
 * Note what this type cannot express: there is no `Wiped`, no `Corrupt`, no
 * `StartOver`. Every failure lands in [NeedsRecovery], which is a screen the
 * user acts on, not a state the app resolves by destroying data. `:core:crypto`
 * makes the same choice in `UnlockFailure`; keeping it at both layers means the
 * guarantee survives the boundary rather than being re-derived at it.
 */
public sealed interface VaultState {

    /** Startup, before the Keystore has been asked. Not a spinner state -- it is genuinely unknown. */
    public data object Initializing : VaultState

    /** No phrase wrap exists. First run: the §7.4 gate has not been completed. */
    public data object NeedsOnboarding : VaultState

    /** A KDF or database open is in flight. The Recovery screen shows progress. */
    public data object Working : VaultState

    /** The DEK is recovered and the database is open. */
    public data object Unlocked : VaultState

    /** Keystore did not deliver. The 24 words are the way through. */
    public data class NeedsRecovery(val reason: RecoveryReason) : VaultState

    /**
     * A schema migration is running behind its own screen (SPEC.md §8.1).
     *
     * Distinct from [Working], which is a spinner inside a screen that already
     * has the user's attention. This *is* the screen: the app is not usable, the
     * work is not cancellable — a half-taken snapshot is worse than none — and
     * the cold-start budget explicitly does not apply to a version-upgrade
     * launch.
     */
    public data class Upgrading(val from: Int, val to: Int) : VaultState

    /**
     * The upgrade could not safely start, or did not finish.
     *
     * **The database has not been migrated and is not open.** Blocking is the
     * correct outcome here: migrating without a rollback point is the whole of
     * what BUG8 is about, so refusing to start is better than starting and
     * hoping.
     */
    public data class UpgradeBlocked(val reason: UpgradeBlockReason) : VaultState
}

/**
 * Why an upgrade did not proceed (SPEC.md §8.1, ADR-0019).
 *
 * Separated by remedy, the same way [RecoveryReason] is: each of these puts a
 * different sentence and a different action in front of the user, and collapsing
 * them would produce a screen that says "something went wrong" — which is the
 * screen that makes a recoverable situation feel terminal.
 */
public sealed interface UpgradeBlockReason {

    /**
     * Not enough free space for the snapshot plus the migration's own working
     * room. The figures are named on screen so "free up space" is actionable
     * rather than vague.
     */
    public data class InsufficientStorage(
        val requiredBytes: Long,
        val availableBytes: Long,
    ) : UpgradeBlockReason

    /**
     * The rollback point could not be taken or did not verify.
     *
     * An unverified snapshot is not a snapshot, exactly as an unverified `.lfbk`
     * is not a backup. The original database is untouched.
     */
    public data object SnapshotFailed : UpgradeBlockReason

    /**
     * The migration threw. [restored] says whether the snapshot went back —
     * the only automatic restore in the app.
     *
     * `false` is the genuinely bad case and the screen must not soften it: the
     * user needs to know before they do anything else.
     */
    public data class MigrationFailed(val restored: Boolean) : UpgradeBlockReason

    /**
     * The database was written by a newer build (BUG3). Never migrated
     * downward, never opened.
     */
    public data class Downgrade(val onDisk: Int, val supported: Int) : UpgradeBlockReason
}

/**
 * Why the silent path did not work.
 *
 * These are distinguished because the Recovery screen says something different
 * for each, and "something different" is the whole difference between a screen
 * that feels recoverable and one that feels like a dead end. ADR-0011 dropped
 * the passphrase wrap partly on the argument that this screen would be *good*;
 * telling the user which of these happened is part of paying that.
 */
public enum class RecoveryReason {
    /**
     * The Keystore key is gone or permanently invalidated -- lock-screen change,
     * biometric re-enrollment on some OEMs, device restore. **The case the
     * whole multi-wrap design exists for**, and entirely recoverable.
     */
    KeystoreUnavailable,

    /** `wrapped_dek_ks.bin` is missing. Same remedy, different sentence. */
    KeystoreWrapMissing,

    /** The blob is present but not parseable or not authentic. */
    KeystoreWrapDamaged,

    /**
     * The DEK unwrapped and the database opened, but the canary row is not ours
     * (D-08). A restored file paired with the wrong key, or a half-applied
     * rotation (§7.7). **Never a wipe** -- the data is very likely intact and
     * belongs to a different key.
     */
    CanaryMismatch,

    /** The DEK is fine but the database would not open. */
    DatabaseUnopenable,
}
