package com.ledgerflow.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.ledgerflow.data.db.dao.CategoryDao
import com.ledgerflow.data.db.dao.SubcategoryDao
import com.ledgerflow.data.db.entity.CategoryEntity
import com.ledgerflow.data.db.entity.SubcategoryEntity
import com.ledgerflow.domain.model.Category
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.CategoryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import javax.inject.Inject

class CategoryRepositoryImpl @Inject constructor(
    private val categoryDao: CategoryDao,
    private val subcategoryDao: SubcategoryDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : CategoryRepository {

    override suspend fun saveCategory(category: Category): Result<Unit> = withContext(ioDispatcher) {
        try {
            if (category.parentId == null) {
                categoryDao.insertCategory(CategoryEntity.fromDomain(category))
            } else {
                subcategoryDao.insertSubcategory(
                    SubcategoryEntity(
                        id = if (category.id >= 1000000L) category.id - 1000000L else category.id,
                        categoryId = category.parentId!!,
                        name = category.name,
                        color = category.color,
                        icon = category.icon,
                        isArchived = category.isArchived,
                        isPinned = category.isPinned
                    )
                )
            }
            Result.Success(Unit)
        } catch (e: SQLiteConstraintException) {
            Result.Failure.ValidationError("A category or subcategory with this name already exists.")
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun deleteCategory(categoryId: Long): Result<Unit> = withContext(ioDispatcher) {
        try {
            if (categoryId >= 1000000L) {
                subcategoryDao.deleteSubcategory(categoryId - 1000000L)
            } else {
                categoryDao.deleteCategory(categoryId)
            }
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun getCategoryById(categoryId: Long): Result<Category?> = withContext(ioDispatcher) {
        try {
            if (categoryId >= 1000000L) {
                val sub = subcategoryDao.getSubcategoryById(categoryId - 1000000L)
                val domain = sub?.let {
                    Category(
                        id = it.id + 1000000L,
                        name = it.name,
                        parentId = it.categoryId,
                        isArchived = it.isArchived,
                        color = it.color,
                        icon = it.icon,
                        isPinned = it.isPinned
                    )
                }
                Result.Success(domain)
            } else {
                val entity = categoryDao.getCategoryById(categoryId)
                Result.Success(entity?.toDomain())
            }
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override fun getCategoriesFlow(): Flow<List<Category>> {
        return combine(
            categoryDao.getCategoriesFlow(),
            subcategoryDao.getSubcategoriesFlow()
        ) { cats, subcats ->
            val mappedCats = cats.map { it.toDomain() }
            val mappedSubcats = subcats.map { sub ->
                Category(
                    id = sub.id + 1000000L,
                    name = sub.name,
                    parentId = sub.categoryId,
                    isArchived = sub.isArchived,
                    color = sub.color,
                    icon = sub.icon,
                    isPinned = sub.isPinned
                )
            }
            mappedCats + mappedSubcats
        }
    }

    override suspend fun mergeCategories(sourceCategoryId: Long, targetCategoryId: Long): Result<Unit> = withContext(ioDispatcher) {
        try {
            categoryDao.mergeCategories(sourceCategoryId, targetCategoryId)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun archiveCategory(categoryId: Long): Result<Unit> = withContext(ioDispatcher) {
        try {
            if (categoryId >= 1000000L) {
                subcategoryDao.archiveSubcategory(categoryId - 1000000L)
            } else {
                categoryDao.archiveCategory(categoryId)
            }
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }
}
