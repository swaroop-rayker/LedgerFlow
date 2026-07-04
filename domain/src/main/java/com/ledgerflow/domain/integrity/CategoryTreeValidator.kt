package com.ledgerflow.domain.integrity

import com.ledgerflow.domain.model.Category

object CategoryTreeValidator {

    /**
     * Scans for cycles in a hierarchical category list.
     * Returns true if there are circular parent-child references.
     */
    fun hasCycles(categories: List<Category>): Boolean {
        val adjList = categories.associate { it.id to (it.parentId ?: -1L) }
        val visited = mutableSetOf<Long>()
        val recursionStack = mutableSetOf<Long>()

        for (cat in categories) {
            if (dfsDetectCycle(cat.id, adjList, visited, recursionStack)) {
                return true
            }
        }
        return false
    }

    private fun dfsDetectCycle(
        id: Long,
        adjList: Map<Long, Long>,
        visited: MutableSet<Long>,
        recursionStack: MutableSet<Long>
    ): Boolean {
        if (id in recursionStack) return true
        if (id in visited) return false

        visited.add(id)
        recursionStack.add(id)

        val parentId = adjList[id]
        if (parentId != null && parentId != -1L) {
            if (dfsDetectCycle(parentId, adjList, visited, recursionStack)) {
                return true
            }
        }

        recursionStack.remove(id)
        return false
    }

    /**
     * Checks if the tree exceeds a maximum depth limit (e.g. depth of 2 for parent-child).
     */
    fun exceedsMaxDepth(categories: List<Category>, maxDepth: Int = 2): Boolean {
        val parentMap = categories.associate { it.id to it.parentId }
        for (cat in categories) {
            var depth = 1
            var currentParent = parentMap[cat.id]
            while (currentParent != null) {
                depth++
                if (depth > maxDepth) {
                    return true
                }
                currentParent = parentMap[currentParent]
            }
        }
        return false
    }

    /**
     * Scans for orphan categories (categories whose parentId does not exist in the list).
     */
    fun getOrphans(categories: List<Category>): List<Category> {
        val existingIds = categories.map { it.id }.toSet()
        return categories.filter { it.parentId != null && it.parentId !in existingIds }
    }
}
