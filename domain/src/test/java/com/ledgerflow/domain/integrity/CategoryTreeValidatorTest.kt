package com.ledgerflow.domain.integrity

import com.ledgerflow.domain.model.Category
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryTreeValidatorTest {

    @Test
    fun testValidTree() {
        val categories = listOf(
            Category(id = 1, name = "Food", parentId = null),
            Category(id = 2, name = "Groceries", parentId = 1),
            Category(id = 3, name = "Restaurants", parentId = 1)
        )
        assertFalse(CategoryTreeValidator.hasCycles(categories))
        assertFalse(CategoryTreeValidator.exceedsMaxDepth(categories))
    }

    @Test
    fun testCycleDetected() {
        val categories = listOf(
            Category(id = 1, name = "A", parentId = 2),
            Category(id = 2, name = "B", parentId = 1)
        )
        assertTrue(CategoryTreeValidator.hasCycles(categories))
    }

    @Test
    fun testExceedsMaxDepth() {
        val categories = listOf(
            Category(id = 1, name = "Root", parentId = null),
            Category(id = 2, name = "Child", parentId = 1),
            Category(id = 3, name = "Grandchild", parentId = 2)
        )
        assertTrue(CategoryTreeValidator.exceedsMaxDepth(categories, maxDepth = 2))
    }
}
