package com.ledgerflow.domain.usecase

import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.model.Transaction
import com.ledgerflow.domain.repository.GlobalSearchRepository
import com.ledgerflow.domain.repository.SearchResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SearchUseCaseTest {

    private class FakeGlobalSearchRepository : GlobalSearchRepository {
        var searchResultToReturn: Result<SearchResult> = Result.Success(SearchResult(emptyList(), emptyList(), emptyList()))
        var lastSearchQuery: String? = null
        val savedQueries = mutableListOf<String>()
        val savedSearches = mutableListOf<String>()

        override suspend fun search(query: String): Result<SearchResult> {
            lastSearchQuery = query
            return searchResultToReturn
        }

        override suspend fun getSearchHistory(): List<String> = savedQueries
        
        override suspend fun saveSearchQuery(query: String) {
            savedQueries.add(query)
        }
        
        override suspend fun clearSearchHistory() {
            savedQueries.clear()
        }
        
        override suspend fun getSavedSearches(): List<String> = savedSearches
        
        override suspend fun saveSearch(query: String) {
            savedSearches.add(query)
        }
        
        override suspend fun deleteSavedSearch(query: String) {
            savedSearches.remove(query)
        }
    }

    @Test
    fun testInvokeWithBlankQueryReturnsEmptySearchResultImmediately() = runBlocking {
        val repository = FakeGlobalSearchRepository()
        val useCase = SearchUseCase(repository)

        val result = useCase("")
        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        assertTrue(data.transactions.isEmpty())
        assertTrue(data.categories.isEmpty())
        assertTrue(data.merchants.isEmpty())
        assertNull(repository.lastSearchQuery)
    }

    @Test
    fun testInvokeWithNonBlankQueryCallsRepositorySearchAndSavesQuery() = runBlocking {
        val repository = FakeGlobalSearchRepository()
        val useCase = SearchUseCase(repository)

        val mockResult = SearchResult(
            transactions = listOf(
                Transaction(
                    id = 1L,
                    amount = 500L,
                    merchant = "Swiggy",
                    category = "Food",
                    timestamp = System.currentTimeMillis()
                )
            ),
            categories = emptyList(),
            merchants = emptyList()
        )
        repository.searchResultToReturn = Result.Success(mockResult)

        val result = useCase("  Swiggy  ") // Test trimming
        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        assertEquals(1, data.transactions.size)
        assertEquals("Swiggy", data.transactions[0].merchant)

        // Verify repository interaction
        assertEquals("Swiggy", repository.lastSearchQuery)
        assertEquals(1, repository.savedQueries.size)
        assertEquals("Swiggy", repository.savedQueries[0])
    }

    @Test
    fun testInvokeWithFailedSearchDoesNotSaveSearchQuery() = runBlocking {
        val repository = FakeGlobalSearchRepository()
        val useCase = SearchUseCase(repository)
        repository.searchResultToReturn = Result.Failure.DatabaseError(Exception("DB Disrupted"))

        val result = useCase("Swiggy")
        assertTrue(result is Result.Failure)

        assertEquals("Swiggy", repository.lastSearchQuery)
        assertTrue(repository.savedQueries.isEmpty())
    }
}
