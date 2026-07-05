package com.ledgerflow.domain.usecase

import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.CategoryRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class ValidateTransactionCategoryUseCase @Inject constructor(
    private val categoryRepository: CategoryRepository
) {
    suspend operator fun invoke(categoryName: String, subcategoryName: String?): Result<Unit> {
        if (categoryName.isBlank()) {
            return Result.Failure.ValidationError("Transaction category cannot be blank.")
        }
        
        val allCategories = categoryRepository.getCategoriesFlow().first()
        val parent = allCategories.find { it.parentId == null && it.name.equals(categoryName, ignoreCase = true) }
            ?: return Result.Failure.ValidationError("Selected category '$categoryName' does not exist or was deleted.")

        if (subcategoryName != null && subcategoryName.isNotBlank()) {
            val sub = allCategories.find { 
                it.parentId == parent.id && it.name.equals(subcategoryName, ignoreCase = true) 
            } ?: return Result.Failure.ValidationError("Selected subcategory '$subcategoryName' does not belong to category '$categoryName' or does not exist.")
        }
        
        return Result.Success(Unit)
    }
}
