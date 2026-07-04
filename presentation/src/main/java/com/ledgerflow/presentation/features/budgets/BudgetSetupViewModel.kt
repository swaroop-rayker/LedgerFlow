package com.ledgerflow.presentation.features.budgets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.domain.model.Budget
import com.ledgerflow.domain.model.Category
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.BudgetRepository
import com.ledgerflow.domain.repository.CategoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BudgetSetupUiState(
    val categories: List<Category> = emptyList(),
    val budgets: List<Budget> = emptyList(),
    val isOperationSuccess: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class BudgetSetupViewModel @Inject constructor(
    private val budgetRepository: BudgetRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BudgetSetupUiState())
    val uiState: StateFlow<BudgetSetupUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            categoryRepository.getCategoriesFlow().combine(
                budgetRepository.getBudgetsFlow()
            ) { cats, budgetsList ->
                BudgetSetupUiState(
                    categories = cats,
                    budgets = budgetsList
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun setBudget(categoryId: Long, amount: Long) {
        viewModelScope.launch {
            val calendar = java.util.Calendar.getInstance()
            calendar.set(java.util.Calendar.DAY_OF_MONTH, 1)
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
            calendar.set(java.util.Calendar.MINUTE, 0)
            calendar.set(java.util.Calendar.SECOND, 0)
            val start = calendar.timeInMillis

            calendar.set(java.util.Calendar.DAY_OF_MONTH, calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH))
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 23)
            calendar.set(java.util.Calendar.MINUTE, 59)
            val end = calendar.timeInMillis

            val budget = Budget(
                0L,
                categoryId,
                amount,
                com.ledgerflow.domain.model.BudgetPeriod.MONTHLY,
                start,
                end
            )
            when (val res = budgetRepository.saveBudget(budget)) {
                is Result.Success -> {
                    _uiState.update { it.copy(isOperationSuccess = true, errorMessage = null) }
                }
                is Result.Failure.ValidationError -> {
                    _uiState.update { it.copy(errorMessage = res.message) }
                }
                is Result.Failure.DatabaseError -> {
                    _uiState.update { it.copy(errorMessage = res.exception.localizedMessage) }
                }
                else -> {}
            }
        }
    }

    fun deleteBudget(categoryId: Long) {
        viewModelScope.launch {
            when (val res = budgetRepository.deleteBudget(categoryId)) {
                is Result.Success -> {
                    _uiState.update { it.copy(isOperationSuccess = true, errorMessage = null) }
                }
                is Result.Failure.DatabaseError -> {
                    _uiState.update { it.copy(errorMessage = res.exception.localizedMessage) }
                }
                else -> {}
            }
        }
    }

    fun resetOperationSuccess() {
        _uiState.update { it.copy(isOperationSuccess = false) }
    }
}
