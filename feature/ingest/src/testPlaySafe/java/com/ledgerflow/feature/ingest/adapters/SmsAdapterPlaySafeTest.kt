package com.ledgerflow.feature.ingest.adapters

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.IngestSourceStatus
import com.ledgerflow.core.domain.ingest.IngestSourceType
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * `playSafe`'s SMS source: present, and permanently inert (SPEC.md §3.1, D-04).
 *
 * The mirror of `SmsAdapterSmsFullTest`. Same class under test, same source
 * type, and an answer that cannot be reconciled with the other flavour's —
 * which is the point. If the two source sets ever drifted into one shared
 * implementation, one of these two files would fail rather than the mistake
 * surfacing as a Play rejection months later.
 */
class SmsAdapterPlaySafeTest {

    @Test
    fun status_isAlwaysUnsupportedInBuild() = runTest {
        assertThat(SmsAdapter().status()).isEqualTo(IngestSourceStatus.UNSUPPORTED_IN_BUILD)
    }

    /**
     * Never [IngestSourceStatus.PERMISSION_REQUIRED], which is the one tempting
     * wrong answer: `RECEIVE_SMS` is genuinely not granted here. But it is also
     * not in this flavour's manifest and never will be, so a prompt could not
     * succeed and offering one would be a dead end dressed as an action.
     */
    @Test
    fun status_isNotMerelyAMissingPermission() = runTest {
        assertThat(SmsAdapter().status()).isNotEqualTo(IngestSourceStatus.PERMISSION_REQUIRED)
    }

    /**
     * It still claims [IngestSourceType.SMS].
     *
     * That is what lets a Settings screen render an "SMS" row in the Play build
     * and explain the absence, from the same loop that renders the working
     * sources — the no-op adapter exists to answer the question, not to hide it.
     */
    @Test
    fun sourceType_isStillSms() {
        assertThat(SmsAdapter().sourceType).isEqualTo(IngestSourceType.SMS)
    }
}
