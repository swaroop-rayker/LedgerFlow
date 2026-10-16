package com.ledgerflow.navigation

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.model.LedgerType
import org.junit.Test

/**
 * Home's "See all" opening the Ledger on the right book (V2-PLAN BUG-C step 4).
 *
 * The request is one-shot. If it stayed on the entry, every later return to the
 * Ledger tab would snap back to the book Home last asked for, overriding the
 * user's own choice on the segmented control.
 */
class LedgerBookRequestTest {

    private val handle = SavedStateHandle()

    @Test
    fun eachBook_roundTrips() {
        LedgerType.entries.forEach { book ->
            LedgerBookRequest.place(handle, book)

            assertThat(LedgerBookRequest.consume(handle)).isEqualTo(book)
        }
    }

    @Test
    fun aRequest_isAppliedOnce() {
        LedgerBookRequest.place(handle, LedgerType.CREDIT)

        assertThat(LedgerBookRequest.consume(handle)).isEqualTo(LedgerType.CREDIT)
        assertThat(LedgerBookRequest.consume(handle)).isNull()
    }

    @Test
    fun noRequest_isNull() {
        assertThat(LedgerBookRequest.consume(handle)).isNull()
    }

    /** Restored state from an older build, say: ignored and cleared, not stuck. */
    @Test
    fun anUnreadableRequest_isIgnoredAndCleared() {
        handle[LedgerBookRequest.KEY] = "BOTH"

        assertThat(LedgerBookRequest.consume(handle)).isNull()
        assertThat(handle.contains(LedgerBookRequest.KEY)).isFalse()
    }
}
