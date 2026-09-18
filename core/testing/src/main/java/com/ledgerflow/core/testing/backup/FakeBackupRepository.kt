package com.ledgerflow.core.testing.backup

import com.ledgerflow.core.domain.backup.BackupOutcome
import com.ledgerflow.core.domain.backup.BackupRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A [BackupRepository] that answers with whatever the test sets, and records
 * what it was asked — including **the words**, so a test can assert that a
 * screen handed over exactly what the user typed and nothing it had kept.
 */
public class FakeBackupRepository(
    /** What [backUpNow] returns. */
    public var outcome: BackupOutcome = BackupOutcome.NoBackupFolder,
    /** Whether a folder is chosen and granted. */
    public var folderChosen: Boolean = true,
) : BackupRepository {

    public val backUpCalls: MutableList<List<String>> = mutableListOf()
    public val chosenFolders: MutableList<String> = mutableListOf()
    public val lastBackup: MutableStateFlow<Long?> = MutableStateFlow(null)

    override suspend fun backUpNow(words: List<String>): BackupOutcome {
        backUpCalls += words
        return outcome
    }

    override fun lastBackupAt(): Flow<Long?> = lastBackup

    override suspend fun hasBackupFolder(): Boolean = folderChosen

    override suspend fun setBackupFolder(treeUri: String) {
        chosenFolders += treeUri
        folderChosen = true
    }
}
