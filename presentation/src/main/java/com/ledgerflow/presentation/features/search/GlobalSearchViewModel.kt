package com.ledgerflow.presentation.features.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.GlobalSearchRepository
import com.ledgerflow.domain.repository.SearchResult
import com.ledgerflow.domain.usecase.SearchUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val searchResults: SearchResult? = null,
    val isLoading: Boolean = false,
    val recentSearches: List<String> = emptyList(),
    val savedSearches: List<String> = emptyList(),
    val errorMessage: String? = null
)

@HiltViewModel
class GlobalSearchViewModel @Inject constructor(
    private val searchUseCase: SearchUseCase,
    private val globalSearchRepository: GlobalSearchRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    init {
        loadHistoryAndSaved()
    }

    fun loadHistoryAndSaved() {
        viewModelScope.launch {
            val history = globalSearchRepository.getSearchHistory()
            val saved = globalSearchRepository.getSavedSearches()
            _uiState.update { it.copy(recentSearches = history, savedSearches = saved) }
        }
    }

    fun updateQuery(newQuery: String) {
        _uiState.update { it.copy(query = newQuery) }
        if (newQuery.trim().isEmpty()) {
            _uiState.update { it.copy(searchResults = null, errorMessage = null) }
        } else {
            performSearch()
        }
    }

    private fun performSearch() {
        val currentQuery = _uiState.value.query.trim()
        if (currentQuery.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val res = searchUseCase(currentQuery)) {
                is Result.Success -> {
                    _uiState.update { it.copy(searchResults = res.data, isLoading = false) }
                    loadHistoryAndSaved() // Refresh history list
                }
                is Result.Failure -> {
                    _uiState.update { 
                        it.copy(
                            errorMessage = "Search failed. Downstream database disruption.",
                            isLoading = false
                        )
                    }
                }
            }
        }
    }

    fun saveCurrentSearch() {
        val currentQuery = _uiState.value.query.trim()
        if (currentQuery.isEmpty()) return

        viewModelScope.launch {
            globalSearchRepository.saveSearch(currentQuery)
            loadHistoryAndSaved()
        }
    }

    fun deleteSavedSearch(q: String) {
        viewModelScope.launch {
            globalSearchRepository.deleteSavedSearch(q)
            loadHistoryAndSaved()
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            globalSearchRepository.clearSearchHistory()
            loadHistoryAndSaved()
        }
    }
}
