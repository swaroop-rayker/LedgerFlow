package com.ledgerflow.presentation.features.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.domain.model.Category
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.CategoryRepository
import com.ledgerflow.domain.usecase.MergeCategoriesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoryManagerUiState(
    val categories: List<Category> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isOperationSuccess: Boolean = false
)

@HiltViewModel
class CategoryManagerViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val mergeCategoriesUseCase: MergeCategoriesUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(CategoryManagerUiState())
    val uiState: StateFlow<CategoryManagerUiState> = _uiState.asStateFlow()

    init {
        loadCategories()
    }

    private fun loadCategories() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            categoryRepository.getCategoriesFlow()
                .catch { e -> _uiState.update { it.copy(isLoading = false, errorMessage = e.localizedMessage) } }
                .collect { list ->
                    _uiState.update { it.copy(isLoading = false, categories = list, errorMessage = null) }
                }
        }
    }

    fun addCategory(name: String, parentId: Long?, color: String? = null, icon: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val newCategory = Category(name = name, parentId = parentId, color = color, icon = icon)
            when (val res = categoryRepository.saveCategory(newCategory)) {
                is Result.Success -> {
                    _uiState.update { it.copy(isLoading = false, isOperationSuccess = true, errorMessage = null) }
                }
                is Result.Failure.ValidationError -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = res.message) }
                }
                is Result.Failure.DatabaseError -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Database error: ${res.exception.localizedMessage}") }
                }
                else -> {}
            }
        }
    }

    fun updateCategory(category: Category) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val res = categoryRepository.saveCategory(category)) {
                is Result.Success -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = null) }
                }
                is Result.Failure.ValidationError -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = res.message) }
                }
                is Result.Failure.DatabaseError -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Database error: ${res.exception.localizedMessage}") }
                }
                else -> {}
            }
        }
    }

    fun deleteCategory(categoryId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val res = categoryRepository.deleteCategory(categoryId)) {
                is Result.Success -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = null) }
                }
                is Result.Failure.DatabaseError -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Database error: ${res.exception.localizedMessage}. Note: Categories referenced by transactions cannot be deleted.") }
                }
                else -> {}
            }
        }
    }

    fun mergeCategories(sourceId: Long, targetId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val res = mergeCategoriesUseCase(sourceId, targetId)) {
                is Result.Success -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = null) }
                }
                is Result.Failure.ValidationError -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = res.message) }
                }
                is Result.Failure.DatabaseError -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Database error: ${res.exception.localizedMessage}") }
                }
                else -> {}
            }
        }
    }

    fun resetOperationSuccess() {
        _uiState.update { it.copy(isOperationSuccess = false) }
    }
}
