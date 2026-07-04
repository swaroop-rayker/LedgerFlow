package com.ledgerflow.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ledgerflow.domain.model.Budget
import com.ledgerflow.domain.model.BudgetPeriod

@Entity(
    tableName = "budgets",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["category_id"], name = "idx_budgets_category")
    ]
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    val amount: Long,
    val period: String,
    @ColumnInfo(name = "start_date") val startDate: Long,
    @ColumnInfo(name = "end_date") val endDate: Long
) {
    fun toDomain() = Budget(
        id = id,
        categoryId = categoryId,
        amount = amount,
        period = BudgetPeriod.valueOf(period),
        startDate = startDate,
        endDate = endDate
    )

    companion object {
        fun fromDomain(domain: Budget) = BudgetEntity(
            id = domain.id,
            categoryId = domain.categoryId,
            amount = domain.amount,
            period = domain.period.name,
            startDate = domain.startDate,
            endDate = domain.endDate
        )
    }
}
