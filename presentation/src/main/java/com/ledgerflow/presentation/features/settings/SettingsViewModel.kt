package com.ledgerflow.presentation.features.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.DataIntegrityRepository
import com.ledgerflow.domain.repository.BackupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val integrityReport: List<String> = emptyList(),
    val fkReport: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val infoMessage: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val integrityRepository: DataIntegrityRepository,
    private val backupRepository: BackupRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun runDiagnostics() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, infoMessage = null, errorMessage = null) }
            val integrityRes = integrityRepository.runDatabaseIntegrityCheck()
            val fkRes = integrityRepository.runForeignKeyCheck()

            val integrityList = when (integrityRes) {
                is Result.Success -> if (integrityRes.data.isEmpty()) listOf("Database Integrity: OK") else integrityRes.data
                is Result.Failure.DatabaseError -> listOf("Integrity Check Failed: ${integrityRes.exception.localizedMessage}")
                else -> listOf("Integrity Check: Unknown state")
            }

            val fkList = when (fkRes) {
                is Result.Success -> if (fkRes.data.isEmpty()) listOf("Foreign Keys: OK") else fkRes.data
                is Result.Failure.DatabaseError -> listOf("Foreign Key Check Failed: ${fkRes.exception.localizedMessage}")
                else -> listOf("Foreign Key Check: Unknown state")
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    integrityReport = integrityList,
                    fkReport = fkList
                )
            }
        }
    }

    fun backupDatabase(destinationUri: Uri, passwordStr: String) {
        viewModelScope.launch {
            if (passwordStr.isBlank()) {
                _uiState.update { it.copy(errorMessage = "Password cannot be empty.") }
                return@launch
            }
            _uiState.update { it.copy(isLoading = true, infoMessage = null, errorMessage = null) }
            when (val res = backupRepository.createBackup(destinationUri, passwordStr.toCharArray())) {
                is Result.Success -> {
                    _uiState.update { it.copy(isLoading = false, infoMessage = "Backup created successfully!") }
                }
                is Result.Failure.ValidationError -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = res.message) }
                }
                is Result.Failure.DatabaseError -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Backup failed: ${res.exception.localizedMessage}") }
                }
                else -> {}
            }
        }
    }

    fun restoreDatabase(sourceUri: Uri, passwordStr: String) {
        viewModelScope.launch {
            if (passwordStr.isBlank()) {
                _uiState.update { it.copy(errorMessage = "Password cannot be empty.") }
                return@launch
            }
            _uiState.update { it.copy(isLoading = true, infoMessage = null, errorMessage = null) }
            when (val res = backupRepository.restoreBackup(sourceUri, passwordStr.toCharArray())) {
                is Result.Success -> {
                    _uiState.update { it.copy(isLoading = false, infoMessage = "Restore completed! Please restart the app.") }
                }
                is Result.Failure.ValidationError -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = res.message) }
                }
                is Result.Failure.DatabaseError -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Restore failed: ${res.exception.localizedMessage}") }
                }
                else -> {}
            }
        }
    }
}
