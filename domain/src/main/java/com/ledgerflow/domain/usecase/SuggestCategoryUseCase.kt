package com.ledgerflow.domain.usecase

import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.MerchantPreferenceRepository
import javax.inject.Inject

class SuggestCategoryUseCase @Inject constructor(
    private val merchantPreferenceRepository: MerchantPreferenceRepository
) {
    suspend operator fun invoke(merchant: String, smsBody: String): Pair<String, String?> {
        val prefRes = merchantPreferenceRepository.getMerchantPreference(merchant)
        if (prefRes is Result.Success && prefRes.data != null) {
            val pref = prefRes.data
            return Pair(pref.preferredCategory, pref.preferredSubcategory)
        }

        val merchantLower = merchant.lowercase()
        when {
            merchantLower.contains("swiggy") -> return Pair("Food", "Delivery")
            merchantLower.contains("zomato") -> return Pair("Food", "Restaurants")
            merchantLower.contains("uber") -> return Pair("Transport", "Taxi")
            merchantLower.contains("irctc") -> return Pair("Travel", "Train")
            merchantLower.contains("amazon") || merchantLower.contains("flipkart") || 
            merchantLower.contains("zepto") || merchantLower.contains("jiomart") -> return Pair("Shopping", null)
            merchantLower.contains("netflix") || merchantLower.contains("spotify") -> return Pair("Subscriptions", null)
        }

        val smsLower = smsBody.lowercase()
        return when {
            smsLower.contains("fuel") || smsLower.contains("petrol") || smsLower.contains("diesel") -> Pair("Transport", "Fuel")
            smsLower.contains("metro") -> Pair("Transport", "Metro")
            smsLower.contains("electricity") || smsLower.contains("power") -> Pair("Bills", "Electricity")
            smsLower.contains("internet") || smsLower.contains("wifi") || smsLower.contains("broadband") -> Pair("Bills", "Internet")
            smsLower.contains("recharge") || smsLower.contains("mobile") || smsLower.contains("jio") || smsLower.contains("airtel") -> Pair("Bills", "Mobile")
            smsLower.contains("movie") || smsLower.contains("theatre") || smsLower.contains("show") -> Pair("Entertainment", null)
            smsLower.contains("hospital") || smsLower.contains("clinic") || smsLower.contains("doc") || smsLower.contains("pharmacy") || smsLower.contains("medical") -> Pair("Healthcare", null)
            smsLower.contains("school") || smsLower.contains("college") || smsLower.contains("tuition") || smsLower.contains("fees") -> Pair("Education", null)
            smsLower.contains("flight") || smsLower.contains("hotel") -> Pair("Travel", null)
            smsLower.contains("groceries") || smsLower.contains("supermarket") || smsLower.contains("mart") -> Pair("Groceries", null)
            else -> Pair("Others", null)
        }
    }
}
