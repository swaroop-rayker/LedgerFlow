package com.ledgerflow.feature.onboarding.restore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.domain.backup.PreviewRestoreUseCase
import com.ledgerflow.core.domain.backup.PutBackOutcome
import com.ledgerflow.core.domain.backup.ReplaceVaultFromBackupUseCase
import com.ledgerflow.core.domain.backup.RestoreFromBackupUseCase
import com.ledgerflow.core.domain.backup.RestorePreview
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
 * Restore from a backup: at first run (§16 Q11), finishing an interrupted one,
 * or from Settings over this phone's ledger (ADR-0033).
 *
 * **From Settings, the words open the backup before anything happens.** Submit
 * there is a [PreviewRestoreUseCase]: the backup is opened in memory and
 * counted, and only then does the confirmation page ask for DELETE. Replace is
 * the one step that changes anything.
 *
 * **How long the words live** — the same rule as "Back up now": in this
 * ViewModel while the screen is open, so one wrong word is fixable without
 * retyping 24; kept across the confirmation page, which only reads them;
 * cleared the moment a restore succeeds and when the screen goes away; never
 * written anywhere, not even to saved state.
 */
@HiltViewModel
public class RestoreViewModel @Inject constructor(
    private val restoreFromBackup: RestoreFromBackupUseCase,
    private val previewRestore: PreviewRestoreUseCase,
    private val replaceVault: ReplaceVaultFromBackupUseCase,
    private val restores: RestoreRepository,
    private val validator: RecoveryPhraseValidator,
) : ViewModel() {

    private val _state = MutableStateFlow(
        RestoreUiState(entry = PhraseEntry(requiredWordCount = validator.wordCount)),
    )
    public val state: StateFlow<RestoreUiState> = _state.asStateFlow()

    /**
     * Which restore this is, from the route. Not a reset: at first run the
     * ViewModel belongs to the activity, and a restore that is interrupted in
     * this process comes back as [RestoreMode.Resuming] with the backup and the
     * words still chosen, so trying again is one tap.
     */
    public fun setMode(mode: RestoreMode) {
        _state.update { it.copy(mode = mode) }
        if (mode == RestoreMode.Resuming) refreshPutBack()
    }

    public fun onEvent(event: RestoreEvent) {
        if (isClosedTo(event)) return
        when (event) {
            is RestoreEvent.FolderChosen -> event.treeUri?.let(::chooseFolder)
            is RestoreEvent.BackupSelected -> selectBackup(event.fileName)
            is RestoreEvent.SingleFileChosen -> event.documentUri?.let(::chooseSingleFile)
            is RestoreEvent.DraftChanged -> updateEntry { it.withDraft(event.value, validator) }
            is RestoreEvent.WordCommitted -> updateEntry { it.commit(event.word) }
            is RestoreEvent.WordRemoved -> updateEntry { it.remove(event.index) }
            RestoreEvent.Submitted -> submit()
            is RestoreEvent.FromSettings -> fromSettings(event)
            RestoreEvent.Continued -> if (_state.value.isDone) viewModelScope.launch { restores.finish() }
            RestoreEvent.Left -> leave()
            is RestoreEvent.Scanner -> scanner(event)
        }
    }

    /** A finished restore is final: the only thing left to do is open it. */
    private fun isClosedTo(event: RestoreEvent): Boolean = _state.value.isDone && event != RestoreEvent.Continued

    private fun fromSettings(event: RestoreEvent.FromSettings) {
        when (event) {
            is RestoreEvent.ConfirmationChanged -> _state.update { it.copy(confirmation = event.value) }
            RestoreEvent.Replaced -> replace()
            RestoreEvent.ConfirmationLeft -> leaveConfirmation()
            RestoreEvent.PutBack -> putBack()
        }
    }

    /**
     * Back out of the screen (BUG30): start over, words and backup both
     * forgotten. Not while a restore runs — the screen does not offer back
     * then, and the answer still has to land somewhere.
     */
    private fun leave() {
        if (_state.value.isWorking) return
        _state.value = RestoreUiState(
            mode = _state.value.mode,
            entry = PhraseEntry(requiredWordCount = validator.wordCount),
        )
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
            if (current.mode == RestoreMode.Replace) {
                when (val preview = previewRestore(source, current.entry.words)) {
                    is RestorePreview.Ready ->
                        _state.update { it.copy(isWorking = false, preview = preview, confirmation = "") }
                    is RestorePreview.Refused ->
                        _state.update { it.copy(isWorking = false, result = preview.reason) }
                }
            } else {
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
    }

    /**
     * The one step that changes anything. Done shows the report; anything else
     * returns to the words with its sentence — a rollback included, which says
     * the phone's ledger is as it was.
     */
    private fun replace() {
        val current = _state.value
        val source = current.source
        if (!current.canReplace || source == null) return
        _state.update { it.copy(isWorking = true) }
        viewModelScope.launch {
            val outcome = replaceVault(source, current.entry.words)
            _state.update {
                it.copy(
                    isWorking = false,
                    result = outcome,
                    preview = if (outcome.isSuccess) it.preview else null,
                    confirmation = "",
                    entry = if (outcome.isSuccess) it.entry.cleared() else it.entry,
                )
            }
        }
    }

    private fun leaveConfirmation() {
        if (_state.value.isWorking) return
        _state.update { it.copy(preview = null, confirmation = "") }
    }

    private fun refreshPutBack() {
        viewModelScope.launch {
            val possible = restores.canPutBack()
            _state.update { it.copy(canPutBack = possible) }
        }
    }

    /**
     * On success the vault opens and the app moves on by itself; the words
     * and the chosen backup are forgotten here first.
     */
    private fun putBack() {
        val current = _state.value
        if (!current.canPutBack || current.isWorking) return
        _state.update { it.copy(isWorking = true, putBackFailed = false) }
        viewModelScope.launch {
            when (restores.putBack()) {
                PutBackOutcome.Done -> _state.value = RestoreUiState(
                    mode = _state.value.mode,
                    entry = PhraseEntry(requiredWordCount = validator.wordCount),
                )
                PutBackOutcome.Failed -> {
                    val stillPossible = restores.canPutBack()
                    _state.update { it.copy(isWorking = false, putBackFailed = true, canPutBack = stillPossible) }
                }
            }
        }
    }

    override fun onCleared() {
        _state.update { it.copy(entry = it.entry.cleared()) }
        super.onCleared()
    }
}
