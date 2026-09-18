package com.ledgerflow.core.database.backup

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.crypto.lfbk.LfbkContainer
import com.ledgerflow.core.crypto.lfbk.LfbkFailure
import com.ledgerflow.core.database.LedgerFlowDatabase
import org.junit.Test

/**
 * BUG26: a backup that authenticated and then did not parse **threw** out of
 * the restore instead of being reported.
 *
 * `DatabaseBackupManager.restore` decoded the payload JSON outside the
 * `runCatching` that guards the import, so a `.lfbk` whose tag verified but
 * whose payload this build could not read — a writer bug, or a format it does
 * not understand — escaped as an exception. Unreachable while restore had no
 * caller; the first-run restore (ADR-0026) would have crashed on it, on the one
 * screen that must never feel hopeless.
 *
 * JVM-only: the container is pure `javax.crypto`, and the parse happens before
 * any database exists.
 */
class Bug26_UnparseablePayloadIsReportedNotThrownTest {

    private val seed = ByteArray(64) { it.toByte() }

    private fun sealed(payload: String): ByteArray =
        LfbkContainer.write(payload.toByteArray(), seed, LedgerFlowDatabase.VERSION)

    @Test
    fun bug26_anAuthenticPayloadThatDoesNotParse_isReportedAsMalformed() {
        val opened = DatabaseBackupManager.open(sealed("this is not json"), seed)

        assertThat(opened).isInstanceOf(OpenedBackup.Failure::class.java)
        assertThat((opened as OpenedBackup.Failure).reason).isInstanceOf(LfbkFailure.Malformed::class.java)
    }

    /** The boundary: a well-formed payload still opens. */
    @Test
    fun bug26_aWellFormedPayload_stillOpens() {
        val payload = """{"schemaVersion":11,"createdAt":0,"appMeta":[],"categories":[],"merchants":[],""" +
            """"paymentMethods":[],"ledgerEntries":[],"lineItems":[]}"""

        val opened = DatabaseBackupManager.open(sealed(payload), seed)

        assertThat(opened).isInstanceOf(OpenedBackup.Ready::class.java)
    }
}
