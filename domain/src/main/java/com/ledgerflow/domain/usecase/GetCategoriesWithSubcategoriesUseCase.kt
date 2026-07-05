package com.ledgerflow.domain.usecase

import com.ledgerflow.domain.model.Category
import com.ledgerflow.domain.model.CategoryWithSubcategories
import com.ledgerflow.domain.repository.CategoryRepository
import com.ledgerflow.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

class GetCategoriesWithSubcategoriesUseCase @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val transactionRepository: TransactionRepository
) {
    operator fun invoke(): Flow<List<CategoryWithSubcategories>> {
        return combine(
            categoryRepository.getCategoriesFlow(),
            transactionRepository.getRecentCategoriesFlow(limit = 15)
        ) { allCategories, recentCategoryNames ->
            val parents = allCategories.filter { it.parentId == null && !it.isArchived }
            val subcategories = allCategories.filter { it.parentId != null && !it.isArchived }
            
            val sortedParents = parents.sortedWith(
                compareByDescending<Category> { it.isPinned }
                    .thenBy { parent ->
                        val index = recentCategoryNames.indexOfFirst { it.equals(parent.name, ignoreCase = true) }
                        if (index == -1) Int.MAX_VALUE else index
                    }
                    .thenBy { it.name.lowercase() }
            )
            
            val subcategoriesByParent = subcategories.groupBy { it.parentId!! }
            
            sortedParents.map { parent ->
                val children = subcategoriesByParent[parent.id] ?: emptyList()
                val sortedChildren = children.sortedWith(
                    compareByDescending<Category> { it.isPinned }
                        .thenBy { it.name.lowercase() }
                )
                CategoryWithSubcategories(
                    category = parent,
                    subcategories = sortedChildren
                )
            }
        }
    }
}
