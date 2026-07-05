package com.ledgerflow.domain.usecase

import com.ledgerflow.domain.model.MerchantPreference
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.MerchantPreferenceRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestCategoryUseCaseTest {

    private class FakeMerchantPreferenceRepository : MerchantPreferenceRepository {
        val preferences = mutableMapOf<String, MerchantPreference>()
        override suspend fun saveMerchantPreference(preference: MerchantPreference): Result<Unit> {
            preferences[preference.merchant.lowercase()] = preference
            return Result.Success(Unit)
        }
        override suspend fun getMerchantPreference(merchant: String): Result<MerchantPreference?> {
            return Result.Success(preferences[merchant.lowercase()])
        }
        override suspend fun incrementUsageCount(merchant: String): Result<Unit> = Result.Success(Unit)
    }

    @Test
    fun testSuggestCategoryWithMerchantRules() = runBlocking {
        val repository = FakeMerchantPreferenceRepository()
        val useCase = SuggestCategoryUseCase(repository)

        // Swiggy rule
        val (cat1, sub1) = useCase("Swiggy Delivery Service", "")
        assertEquals("Food", cat1)
        assertEquals("Delivery", sub1)

        // Zepto rule
        val (cat2, sub2) = useCase("Zepto order #123", "")
        assertEquals("Shopping", cat2)
        assertEquals(null, sub2)

        // JioMart rule
        val (cat3, sub3) = useCase("JioMart online store", "")
        assertEquals("Shopping", cat3)
        assertEquals(null, sub3)

        // Uber rule
        val (cat4, sub4) = useCase("Uber India ride", "")
        assertEquals("Transport", cat4)
        assertEquals("Taxi", sub4)
    }

    @Test
    fun testSuggestCategoryWithKeywordRules() = runBlocking {
        val repository = FakeMerchantPreferenceRepository()
        val useCase = SuggestCategoryUseCase(repository)

        // Fuel keyword
        val (cat1, sub1) = useCase("HPCL petrol pump", "debited for petrol at fuel station")
        assertEquals("Transport", cat1)
        assertEquals("Fuel", sub1)

        // Electricity keyword
        val (cat2, sub2) = useCase("BESCOM", "electricity bill payment of Rs. 1500")
        assertEquals("Bills", cat2)
        assertEquals("Electricity", sub2)

        // Default Others
        val (cat3, sub3) = useCase("Some Unknown Store", "payment of Rs. 500")
        assertEquals("Others", cat3)
        assertEquals(null, sub3)
    }

    @Test
    fun testSuggestCategoryWithSavedPreference() = runBlocking {
        val repository = FakeMerchantPreferenceRepository()
        val useCase = SuggestCategoryUseCase(repository)

        // Prepopulate preference
        repository.saveMerchantPreference(
            MerchantPreference(
                merchant = "Some Store",
                preferredCategory = "Healthcare",
                preferredSubcategory = null,
                lastUsed = System.currentTimeMillis(),
                usageCount = 1
            )
        )

        val (cat, sub) = useCase("Some Store", "")
        assertEquals("Healthcare", cat)
        assertEquals(null, sub)
    }
}
