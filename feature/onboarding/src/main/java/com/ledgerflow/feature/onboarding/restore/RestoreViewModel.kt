package com.ledgerflow.feature.onboarding.restore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.domain.backup.RestoreFromBackupUseCase
import com.ledgerflow.core.domain.backup.RestoreRepository
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
 * Restore from a backup (§16 Q11).
 *
 * **How long the words live** — the same rule as "Back up now": in this
 * ViewModel while the screen is open, so one wrong word is fixable without
 * retyping 24; cleared the moment a restore succeeds and when the screen goes
 * away; never written anywhere, not even to saved state.
 */
@HiltViewModel
public class RestoreViewModel @Inject constructor(
    private val restoreFromBackup: RestoreFromBackupUseCase,
    private val restores: RestoreRepository,
    private val validator: RecoveryPhraseValidator,
) : ViewModel() {

    private val _state = MutableStateFlow(
        RestoreUiState(entry = PhraseEntry(requiredWordCount = validator.wordCount)),
    )
    public val state: StateFlow<RestoreUiState> = _state.asStateFlow()

    public fun onEvent(event: RestoreEvent) {
        // A finished restore is final: the only thing left to do is open it.
        if (_state.value.isDone && event != RestoreEvent.Continued) return
        when (event) {
            is RestoreEvent.FolderChosen -> event.treeUri?.let(::chooseFolder)
            is RestoreEvent.BackupSelected -> selectBackup(event.fileName)
            is RestoreEvent.SingleFileChosen -> event.documentUri?.let(::chooseSingleFile)
            is RestoreEvent.DraftChanged -> updateEntry { it.withDraft(event.value, validator) }
            is RestoreEvent.WordCommitted -> updateEntry { it.commit(event.word) }
            is RestoreEvent.WordRemoved -> updateEntry { it.remove(event.index) }
            RestoreEvent.Submitted -> submit()
            RestoreEvent.Continued -> if (_state.value.isDone) viewModelScope.launch { restores.finish() }
            RestoreEvent.Left -> leave()
            is RestoreEvent.Scanner -> scanner(event)
        }
    }

    /**
     * Back to onboarding (BUG30): start over, words and backup both forgotten.
     * Not while a restore runs — the screen does not offer back then, and the
     * answer still has to land somewhere.
     */
    private fun leave() {
        if (_state.value.isWorking) return
        _state.value = RestoreUiState(entry = PhraseEntry(requiredWordCount = validator.wordCount))
    }

    private fun chooseFolder(treeUri: String) {
        viewModelScope.launch {
            val backups = restores.listBackups(treeUri)
            _state.update {
                it.copy(
                    treeUri = treeUri,
                    backups = backups.orEmpty(),
                    // Newest first, so the default is the most recent backup.
                    selectedBackup = backups?.firstOrNull(),
                    folderUnreadable = backups == null,
                    singleFileUri = null,
                    result = null,
                )
            }
        }
    }

    private fun selectBackup(fileName: String) {
        _state.update {
            if (fileName in it.backups) it.copy(selectedBackup = fileName, result = null) else it
        }
    }

    private fun chooseSingleFile(documentUri: String) {
        _state.update {
            it.copy(
                singleFileUri = documentUri,
                treeUri = null,
                backups = emptyList(),
                selectedBackup = null,
                folderUnreadable = false,
                result = null,
            )
        }
    }

    /** A scanned Recovery Kit, through the same [PhraseEntry] typed words go through. */
    private fun scanner(event: RestoreEvent.Scanner) {
        when (event) {
            RestoreEvent.Scanner.Requested -> _state.update { it.copy(isScanning = true, scanMessage = null) }
            RestoreEvent.Scanner.Dismissed -> _state.update { it.copy(isScanning = false) }
            is RestoreEvent.Scanner.Read -> scanned(event.text)
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
        val source = current.source
        if (!current.canSubmit || source == null) return
        _state.update { it.copy(isWorking = true, result = null) }
        viewModelScope.launch {
            val outcome = restoreFromBackup(source, current.entry.words)
            _state.update {
                it.copy(
                    isWorking = false,
                    result = outcome,
                    entry = if (outcome.isSuccess) it.entry.cleared() else it.entry,
                )
            }
        }
    }

    override fun onCleared() {
        _state.update { it.copy(entry = it.entry.cleared()) }
        super.onCleared()
    }
}
