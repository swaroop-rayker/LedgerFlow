package com.ledgerflow.presentation.features.developer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.domain.model.DatabaseStats
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.DatabaseToolsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DatabaseToolsUiState(
    val isLoading: Boolean = false,
    val stats: DatabaseStats? = null,
    val infoMessage: String? = null,
    val errorMessage: String? = null,
    val selectedTables: Set<String> = emptySet()
)

@HiltViewModel
class DatabaseToolsViewModel @Inject constructor(
    private val repository: DatabaseToolsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DatabaseToolsUiState())
    val uiState: StateFlow<DatabaseToolsUiState> = _uiState.asStateFlow()

    init {
        loadStats()
    }

    fun loadStats() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val result = repository.getDatabaseStats()) {
                is Result.Success -> {
                    _uiState.update { it.copy(isLoading = false, stats = result.data) }
                }
                is Result.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load database statistics"
                        )
                    }
                }
            }
        }
    }

    fun toggleTableSelection(table: String) {
        _uiState.update { state ->
            val updated = if (state.selectedTables.contains(table)) {
                state.selectedTables - table
            } else {
                state.selectedTables + table
            }
            state.copy(selectedTables = updated)
        }
    }

    fun clearExpenses() = runResetAction { repository.clearExpenses() }
    fun clearPendingTransactions() = runResetAction { repository.clearPendingTransactions() }
    fun clearMerchantPreferences() = runResetAction { repository.clearMerchantPreferences() }
    fun clearCategories() = runResetAction { repository.clearCategories() }
    fun clearSubcategories() = runResetAction { repository.clearSubcategories() }
    fun resetCategoriesToDefault() = runResetAction { repository.resetCategoriesToDefault() }
    fun resetMerchantLearning() = runResetAction { repository.clearMerchantPreferences() }
    fun clearReportsCache() = runResetAction { Result.Success(Unit) }
    fun clearAnalyticsCache() = runResetAction { Result.Success(Unit) }

    fun factoryReset() = runResetAction { repository.factoryReset() }

    fun selectiveReset() {
        val tables = _uiState.value.selectedTables.toList()
        if (tables.isEmpty()) return
        runResetAction { repository.selectiveReset(tables) }
    }

    fun runVacuum() = runResetAction { repository.runVacuum() }

    fun runIntegrityCheck() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, infoMessage = null, errorMessage = null) }
            when (val result = repository.runIntegrityCheck()) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            infoMessage = "Integrity Check Result: ${result.data}"
                        )
                    }
                }
                is Result.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Integrity check failed: ${result.toString()}"
                        )
                    }
                }
            }
        }
    }

    fun runForeignKeyCheck() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, infoMessage = null, errorMessage = null) }
            when (val result = repository.runForeignKeyCheck()) {
                is Result.Success -> {
                    val msg = if (result.data.isEmpty()) "No foreign key violations found." else "${result.data.size} violations found:\n${result.data.joinToString("\n")}"
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            infoMessage = msg
                        )
                    }
                }
                is Result.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Foreign key check failed: ${result.toString()}"
                        )
                    }
                }
            }
        }
    }

    fun createSnapshot() = runResetAction { repository.createSnapshot() }

    fun restoreSnapshot() = runResetAction { repository.restoreSnapshot() }

    private fun runResetAction(action: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, infoMessage = null, errorMessage = null) }
            when (val result = action()) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            infoMessage = "Database operation completed successfully!",
                            selectedTables = emptySet()
                        )
                    }
                    loadStats()
                }
                is Result.Failure -> {
                    val errorMsg = when (result) {
                        is Result.Failure.DatabaseError -> result.exception.message ?: "Database error"
                        is Result.Failure.ValidationError -> result.message
                        is Result.Failure.SecurityError -> result.reason
                        else -> "Unknown error"
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Operation failed: $errorMsg"
                        )
                    }
                }
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(infoMessage = null, errorMessage = null) }
    }
}
