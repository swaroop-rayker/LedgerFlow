package com.ledgerflow.core.testing.backup

import com.ledgerflow.core.domain.backup.RestoreOutcome
import com.ledgerflow.core.domain.backup.RestoreRepository
import com.ledgerflow.core.domain.backup.RestoreSource

/**
 * A [RestoreRepository] that answers with whatever the test sets, and records
 * what it was asked — the source and **the words**, so a test can assert the
 * screen handed over exactly what the user chose and typed.
 */
public class FakeRestoreRepository(
    /** What [restore] returns. */
    public var outcome: RestoreOutcome = RestoreOutcome.Failed,
    /** What [listBackups] returns for any folder; null means unreadable. */
    public var backups: List<String>? = emptyList(),
) : RestoreRepository {

    public val restoreCalls: MutableList<Pair<RestoreSource, List<String>>> = mutableListOf()
    public val listedFolders: MutableList<String> = mutableListOf()
    public var finishCalls: Int = 0

    override suspend fun listBackups(treeUri: String): List<String>? {
        listedFolders += treeUri
        return backups
    }

    override suspend fun restore(source: RestoreSource, words: List<String>): RestoreOutcome {
        restoreCalls += source to words
        return outcome
    }

    override suspend fun finish() {
        finishCalls++
    }
}
