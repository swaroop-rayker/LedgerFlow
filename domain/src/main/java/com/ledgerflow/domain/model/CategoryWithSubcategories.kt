package com.ledgerflow.domain.model

data class CategoryWithSubcategories(
    val category: Category,
    val subcategories: List<Category>
)
