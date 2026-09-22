package com.ledgerflow.feature.settings.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.domain.backup.BackUpNowUseCase
import com.ledgerflow.core.domain.backup.BackupOutcome
import com.ledgerflow.core.domain.backup.BackupRepository
import com.ledgerflow.core.domain.vault.PhraseEntry
import com.ledgerflow.core.domain.vault.PhraseScan
import com.ledgerflow.core.domain.vault.applyScan
import com.ledgerflow.core.domain.vault.RecoveryPhraseValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * "Back up now" (§16 Q23).
 *
 * **How long the words live.** They exist in this ViewModel while the screen
 * is open, because a wrong word should be fixable without retyping 24. They
 * are cleared the moment a backup succeeds, and when the screen goes away
 * ([onCleared]). They are never written anywhere: not to a draft, not to
 * saved state — process death takes them with it, which for these words is
 * the right outcome.
 */
@HiltViewModel
public class BackupNowViewModel @Inject constructor(
    private val backUpNow: BackUpNowUseCase,
    private val backups: BackupRepository,
    private val validator: RecoveryPhraseValidator,
) : ViewModel() {

    private val _state = MutableStateFlow(
        BackupNowUiState(entry = PhraseEntry(requiredWordCount = validator.wordCount)),
    )
    public val state: StateFlow<BackupNowUiState> = _state.asStateFlow()

    init {
        // Asked up front, so the picker is offered before the user types 24
        // words for a backup that has nowhere to go.
        viewModelScope.launch { refreshFolder() }
        // And whether nightly backups are already on, because the screen says
        // different things either way (ADR-0027).
        viewModelScope.launch {
            backups.nightlyBackupsEnabled().collect { enabled ->
                _state.update { it.copy(nightlyBackupsEnabled = enabled) }
            }
        }
        // And whether the last automatic pass failed, which is the one thing
        // about it the user cannot otherwise find out (ADR-0027, amended).
        viewModelScope.launch {
            backups.lastNightlyAttempt().collect { attempt ->
                _state.update { it.copy(failedNightlyAt = attempt?.takeIf { a -> a.failed }?.at) }
            }
        }
    }

    public fun onEvent(event: BackupNowEvent) {
        when (event) {
            is BackupNowEvent.DraftChanged -> updateEntry { it.withDraft(event.value, validator) }
            is BackupNowEvent.WordCommitted -> updateEntry { it.commit(event.word) }
            is BackupNowEvent.WordRemoved -> updateEntry { it.remove(event.index) }
            BackupNowEvent.Submitted -> submit()
            is BackupNowEvent.FolderChosen -> event.treeUri?.let(::chooseFolder)
            BackupNowEvent.ResultDismissed -> _state.update { it.copy(result = null) }
            is BackupNowEvent.Scanner -> scanner(event)
        }
    }

    /** A scanned Recovery Kit, through the same [PhraseEntry] typed words go through. */
    private fun scanner(event: BackupNowEvent.Scanner) {
        when (event) {
            BackupNowEvent.Scanner.Requested -> _state.update { it.copy(isScanning = true, scanMessage = null) }
            BackupNowEvent.Scanner.Dismissed -> _state.update { it.copy(isScanning = false) }
            is BackupNowEvent.Scanner.Read -> scanned(event.text)
        }
    }

    private fun scanned(text: String) {
        when (val scan = _state.value.entry.applyScan(text, validator)) {
            is PhraseScan.Filled ->
                _state.update { it.copy(entry = scan.entry, isScanning = false, scanMessage = null, result = null) }

            is PhraseScan.Rejected -> _state.update { it.copy(isScanning = false, scanMessage = scan.message) }
            PhraseScan.KeepLooking -> Unit
        }
    }

    private fun updateEntry(change: (PhraseEntry) -> PhraseEntry) {
        _state.update { current ->
            val entry = change(current.entry)
            // A result was about the words as they were; editing them makes it stale.
            if (entry == current.entry) current else current.copy(entry = entry, result = null)
        }
    }

    private fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(isWorking = true, result = null) }
        viewModelScope.launch {
            val outcome = backUpNow(current.entry.words)
            _state.update {
                it.copy(
                    isWorking = false,
                    result = outcome,
                    entry = if (outcome.isSuccess) it.entry.cleared() else it.entry,
                )
            }
            // A backup that found no folder may have found it gone since the
            // screen opened (BUG27); ask again rather than trust the old answer.
            if (outcome == BackupOutcome.NoBackupFolder) refreshFolder()
        }
    }

    private fun chooseFolder(treeUri: String) {
        viewModelScope.launch {
            val hadFolder = _state.value.folderName != null
            backups.setBackupFolder(treeUri)
            refreshFolder()
            _state.update {
                val hasFolder = it.folderName != null
                it.copy(
                    folderChanged = it.folderChanged || hadFolder && hasFolder,
                    // "Choose a folder first" is no longer true.
                    result = it.result.takeUnless { r -> r == BackupOutcome.NoBackupFolder && hasFolder },
                )
            }
        }
    }

    private suspend fun refreshFolder() {
        val name = backups.backupFolderName()
        _state.update { it.copy(folderName = name, folderChecked = true) }
    }

    override fun onCleared() {
        _state.update { it.copy(entry = it.entry.cleared()) }
        super.onCleared()
    }
}
