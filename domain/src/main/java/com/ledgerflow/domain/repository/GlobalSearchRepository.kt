package com.ledgerflow.domain.repository

import com.ledgerflow.domain.model.Transaction
import com.ledgerflow.domain.model.Category
import com.ledgerflow.domain.model.Merchant
import com.ledgerflow.domain.model.Result

data class SearchResult(
    val transactions: List<Transaction>,
    val categories: List<Category>,
    val merchants: List<Merchant>
)

interface GlobalSearchRepository {
    suspend fun search(query: String): Result<SearchResult>
    suspend fun getSearchHistory(): List<String>
    suspend fun saveSearchQuery(query: String)
    suspend fun clearSearchHistory()
    suspend fun getSavedSearches(): List<String>
    suspend fun saveSearch(query: String)
    suspend fun deleteSavedSearch(query: String)
}
