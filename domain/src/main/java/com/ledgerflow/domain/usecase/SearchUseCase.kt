package com.ledgerflow.domain.usecase

import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.GlobalSearchRepository
import com.ledgerflow.domain.repository.SearchResult
import javax.inject.Inject

class SearchUseCase @Inject constructor(
    private val globalSearchRepository: GlobalSearchRepository
) {
    suspend operator fun invoke(query: String): Result<SearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return Result.Success(SearchResult(emptyList(), emptyList(), emptyList()))
        }
        
        val result = globalSearchRepository.search(trimmed)
        if (result is Result.Success) {
            globalSearchRepository.saveSearchQuery(trimmed)
        }
        return result
    }
}
