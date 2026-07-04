package com.ledgerflow.domain.usecase

import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.CategoryRepository
import javax.inject.Inject

class MergeCategoriesUseCase @Inject constructor(
    private val categoryRepository: CategoryRepository
) {
    suspend operator fun invoke(sourceCategoryId: Long, targetCategoryId: Long): Result<Unit> {
        if (sourceCategoryId == targetCategoryId) {
            return Result.Failure.ValidationError("Cannot merge a category into itself.")
        }
        return categoryRepository.mergeCategories(sourceCategoryId, targetCategoryId)
    }
}
