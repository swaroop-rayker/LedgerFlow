package com.ledgerflow.feature.ingest.adapters

import com.ledgerflow.core.domain.ingest.IngestSourceStatus
import com.ledgerflow.core.domain.ingest.IngestSourceType
import com.ledgerflow.core.domain.ingest.TransactionIngestSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The SMS source in the **`playSafe`** flavour: present, and permanently inert
 * (SPEC.md §3.1, D-04).
 *
 * Same fully-qualified name as `smsFull`'s adapter, deliberately. The shared
 * Hilt module in `src/main` binds `SmsAdapter` into the source set once, for
 * both flavours; the flavour source set decides which body that name resolves
 * to. That is the mechanism by which the difference between the two builds is
 * *which object answers*, not an `if` at any call site — CLAUDE.md §0.
 *
 * ## Why it exists at all rather than simply being absent
 *
 * Absence would work for the compiler — a multibinding tolerates a smaller set —
 * and it would be worse for the user. `playSafe` is the Play build, where the
 * reason there is no SMS ingest is a policy the user did not choose and cannot
 * fix. A source that reports [IngestSourceStatus.UNSUPPORTED_IN_BUILD] lets
 * Settings show the row and say so; an empty slot leaves the same question
 * unanswered, and the likely reading is that the feature is broken.
 *
 * It holds no `Context`, registers no receiver, and there is no `RECEIVE_SMS`
 * anywhere in this flavour's manifest to register one against. There is nothing
 * here to turn on later: turning it on means installing the other flavour.
 */
@Singleton
public class SmsAdapter @Inject constructor() : TransactionIngestSource {

    override val sourceType: IngestSourceType = IngestSourceType.SMS

    /**
     * Constant, and does not consult the platform.
     *
     * Not an oversight worth "fixing" by checking the permission: `RECEIVE_SMS`
     * is not in this manifest, so the check could only ever return denied, and
     * reporting [IngestSourceStatus.PERMISSION_REQUIRED] would put a button in
     * front of the user that can never succeed.
     */
    override suspend fun status(): IngestSourceStatus = IngestSourceStatus.UNSUPPORTED_IN_BUILD
}
