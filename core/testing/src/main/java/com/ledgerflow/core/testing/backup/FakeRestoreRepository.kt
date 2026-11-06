package com.ledgerflow.core.testing.backup

import com.ledgerflow.core.domain.backup.LedgerCounts
import com.ledgerflow.core.domain.backup.PutBackOutcome
import com.ledgerflow.core.domain.backup.RestoreOutcome
import com.ledgerflow.core.domain.backup.RestorePreview
import com.ledgerflow.core.domain.backup.RestoreRepository
import com.ledgerflow.core.domain.backup.RestoreSource

/**
 * A [RestoreRepository] that answers with whatever the test sets, and records
 * what it was asked — the source and **the words**, so a test can assert the
 * screen handed over exactly what the user chose and typed.
 */
public class FakeRestoreRepository(
    /** What [restore] and [replace] return. */
    public var outcome: RestoreOutcome = RestoreOutcome.Failed,
    /** What [listBackups] returns for any folder; null means unreadable. */
    public var backups: List<String>? = emptyList(),
    /** What [preview] returns. */
    public var previewResult: RestorePreview = RestorePreview.Ready(
        backup = LedgerCounts(expenses = 412, income = 38, inbox = 5),
        phone = LedgerCounts(expenses = 430, income = 41, inbox = 9),
        sameWords = true,
    ),
    /** What [canPutBack] returns. */
    public var setAside: Boolean = false,
    /** What [putBack] returns. */
    public var putBackResult: PutBackOutcome = PutBackOutcome.Done,
) : RestoreRepository {

    public val restoreCalls: MutableList<Pair<RestoreSource, List<String>>> = mutableListOf()
    public val previewCalls: MutableList<Pair<RestoreSource, List<String>>> = mutableListOf()
    public val replaceCalls: MutableList<Pair<RestoreSource, List<String>>> = mutableListOf()
    public val listedFolders: MutableList<String> = mutableListOf()
    public var finishCalls: Int = 0
    public var putBackCalls: Int = 0

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

    override suspend fun preview(source: RestoreSource, words: List<String>): RestorePreview {
        previewCalls += source to words
        return previewResult
    }

    override suspend fun replace(source: RestoreSource, words: List<String>): RestoreOutcome {
        replaceCalls += source to words
        return outcome
    }

    override suspend fun canPutBack(): Boolean = setAside

    override suspend fun putBack(): PutBackOutcome {
        putBackCalls++
        return putBackResult
    }
}
