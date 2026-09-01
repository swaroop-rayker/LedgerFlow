package com.ledgerflow.core.domain.ingest

/**
 * Whether the first-run notification explainer has been shown (SPEC.md §5.2).
 *
 * §5.2 asks for the explainer at first run. `SPEC.md` §7.4's gate cannot host
 * it — the last gate step is where the vault is *created*, so a step after it
 * would run while the app has already switched away from onboarding — so the
 * explainer is the first thing shown once the vault exists, and this is what
 * stops it being the first thing shown every time after that.
 *
 * **Deliberately not `app_meta`, and not for tidiness** (ADR-0020). `app_meta`
 * travels in a `.lfbk` (§16 Q13). A restore onto a new phone would arrive with
 * this already true, suppressing the explainer on a device where notification
 * access has never been granted — the one moment the screen exists for. This
 * store does not travel, so the new device asks.
 *
 * Separate from [ListenerHealthStore] despite sharing a file: one is about the
 * service's liveness and is written by the service, the other is about what the
 * user has been shown and is written by the UI. Folding them would give two
 * unrelated callers one interface and a reason to depend on each other's
 * methods.
 */
public interface NotificationSetupStore {

    /** True once the explainer has been presented, however the user left it. */
    public suspend fun hasSeenSetup(): Boolean

    /**
     * The explainer has been presented.
     *
     * Recorded when the user *leaves* the screen, not when they grant anything.
     * Declining is a legitimate answer and re-asking on the next launch would
     * make it a nag; the Dashboard banner and the Settings row are the standing
     * routes back, and they are always there.
     */
    public suspend fun markSetupSeen()
}
