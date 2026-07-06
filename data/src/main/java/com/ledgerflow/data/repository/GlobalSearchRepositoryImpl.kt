package com.ledgerflow.data.repository

import com.ledgerflow.data.db.LedgerFlowDatabase
import com.ledgerflow.data.datastore.SecurityPrefsManager
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.GlobalSearchRepository
import com.ledgerflow.domain.repository.SearchResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GlobalSearchRepositoryImpl @Inject constructor(
    private val database: LedgerFlowDatabase,
    private val securityPrefsManager: SecurityPrefsManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : GlobalSearchRepository {

    override suspend fun search(query: String): Result<SearchResult> = withContext(ioDispatcher) {
        try {
            val transactions = database.transactionDao().searchTransactions(query).map { it.toDomain() }
            val categories = database.categoryDao().searchCategories(query).map { it.toDomain() }
            val merchants = database.merchantDao().searchMerchants(query).map { entity ->
                val aliases = database.merchantAliasDao().getAliasesForMerchant(entity.id).map { it.alias }
                val regexes = database.merchantRegexDao().getRegexesForMerchant(entity.id).map { it.pattern }
                entity.toDomain(aliases, regexes)
            }

            Result.Success(SearchResult(transactions, categories, merchants))
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun getSearchHistory(): List<String> = withContext(ioDispatcher) {
        securityPrefsManager.getSearchHistory()
    }

    override suspend fun saveSearchQuery(query: String) = withContext(ioDispatcher) {
        securityPrefsManager.saveSearchQuery(query)
    }

    override suspend fun clearSearchHistory() = withContext(ioDispatcher) {
        securityPrefsManager.clearSearchHistory()
    }

    override suspend fun getSavedSearches(): List<String> = withContext(ioDispatcher) {
        securityPrefsManager.getSavedSearches()
    }

    override suspend fun saveSearch(query: String) = withContext(ioDispatcher) {
        securityPrefsManager.saveSearch(query)
    }

    override suspend fun deleteSavedSearch(query: String) = withContext(ioDispatcher) {
        securityPrefsManager.deleteSavedSearch(query)
    }
}
