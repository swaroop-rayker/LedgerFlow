package com.ledgerflow.core.domain.ingest

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.usecase.GetIngestSourceStatusUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The source-agnostic read, which is the whole claim S11 makes (SPEC.md §3.1,
 * CLAUDE.md §0).
 *
 * What is actually under test is a negative: that asking every capture source
 * the same question and rendering the answers takes no knowledge of which
 * source is which. The fakes below stand in for the two real adapters and for
 * the `playSafe` no-op, and the use case cannot tell them apart — if it ever
 * grows an `if (sourceType == SMS)`, one of these stops holding.
 */
class GetIngestSourceStatusUseCaseTest {

    private class FakeSource(
        override val sourceType: IngestSourceType,
        private val status: IngestSourceStatus,
    ) : TransactionIngestSource {
        var statusReads: Int = 0
            private set

        override suspend fun status(): IngestSourceStatus {
            statusReads++
            return status
        }
    }

    private fun useCase(vararg sources: TransactionIngestSource) =
        GetIngestSourceStatusUseCase(sources.toSet())

    @Test
    fun invoke_withBothSources_reportsEachOneKeyedByType() = runTest {
        val statuses = useCase(
            FakeSource(IngestSourceType.SMS, IngestSourceStatus.READY),
            FakeSource(IngestSourceType.NOTIFICATION, IngestSourceStatus.PERMISSION_REQUIRED),
        ).invoke()

        assertThat(statuses).containsExactly(
            IngestSourceType.SMS, IngestSourceStatus.READY,
            IngestSourceType.NOTIFICATION, IngestSourceStatus.PERMISSION_REQUIRED,
        )
    }

    /**
     * The `playSafe` shape: the SMS source is present and permanently
     * unsupported. It must come back in the map like any other — a caller that
     * wants to explain *why* SMS ingest is absent cannot do so if the row is
     * missing, and an empty slot reads as a bug rather than as a policy.
     */
    @Test
    fun invoke_withAnUnsupportedSource_stillReportsIt() = runTest {
        val statuses = useCase(
            FakeSource(IngestSourceType.SMS, IngestSourceStatus.UNSUPPORTED_IN_BUILD),
            FakeSource(IngestSourceType.NOTIFICATION, IngestSourceStatus.READY),
        ).invoke()

        assertThat(statuses[IngestSourceType.SMS])
            .isEqualTo(IngestSourceStatus.UNSUPPORTED_IN_BUILD)
        assertThat(statuses).hasSize(2)
    }

    /**
     * A build that somehow binds no sources answers "none", rather than throwing
     * on a caller that has no way to recover.
     */
    @Test
    fun invoke_withNoSources_isEmpty() = runTest {
        assertThat(useCase().invoke()).isEmpty()
    }

    /**
     * Status is read on every call, never cached.
     *
     * The grants this reflects live outside the app -- notification access is a
     * Settings toggle, and an OEM battery killer can drop the listener without
     * telling anyone (§5.2). A cached answer would leave a health banner
     * insisting everything is fine.
     */
    @Test
    fun invoke_calledTwice_readsStatusAgain() = runTest {
        val source = FakeSource(IngestSourceType.NOTIFICATION, IngestSourceStatus.READY)
        val useCase = useCase(source)

        useCase()
        useCase()

        assertThat(source.statusReads).isEqualTo(2)
    }
}
