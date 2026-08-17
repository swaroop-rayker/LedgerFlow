package com.ledgerflow.core.common.time

/**
 * The current time, injected.
 *
 * `System.currentTimeMillis()` called inline is untestable in exactly the places
 * it matters most here: soft-delete timestamps, `updated_at`, the draft debounce,
 * and the ±3-minute cross-source dedupe window at P2. A test that has to sleep to
 * exercise those is a test that will be flaky on CI.
 *
 * A `fun interface` rather than `java.time.Clock` because callers only ever want
 * epoch millis, and the platform type invites `Instant.now()` allocations on hot
 * paths for no benefit.
 */
public fun interface Clock {
    public fun nowMillis(): Long

    public companion object {
        public val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}
