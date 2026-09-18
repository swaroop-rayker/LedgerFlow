package com.ledgerflow.core.data.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Which files the restore offers, and in what order. */
class BackupNamesTest {

    @Test
    fun theAppsBackups_newestFirst_thenAnyRenamedOne() {
        val listed = BackupNames.newestFirst(
            listOf(
                "ledgerflow-20260911-093000.lfbk",
                "notes.txt",
                "before-the-move.lfbk",
                "ledgerflow-20260918-101500.lfbk",
                "ledgerflow-20260918-101500.lfbk.tmp",
            ),
        )

        assertThat(listed).containsExactly(
            "ledgerflow-20260918-101500.lfbk",
            "ledgerflow-20260911-093000.lfbk",
            "before-the-move.lfbk",
        ).inOrder()
    }

    /** A write in progress is not a backup yet. */
    @Test
    fun aTemporaryFile_isNeverOffered() {
        assertThat(BackupNames.newestFirst(listOf("ledgerflow-20260918-101500.lfbk.tmp"))).isEmpty()
    }
}
