package com.ledgerflow.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.ledgerflow.domain.model.Frequency
import com.ledgerflow.domain.model.RecurringRule

@Entity(tableName = "recurring_rules")
data class RecurringRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val frequency: String,
    val interval: Int,
    @ColumnInfo(name = "end_date") val endDate: Long?
) {
    fun toDomain() = RecurringRule(
        id = id,
        frequency = Frequency.valueOf(frequency),
        interval = interval,
        endDate = endDate
    )

    companion object {
        fun fromDomain(domain: RecurringRule) = RecurringRuleEntity(
            id = domain.id,
            frequency = domain.frequency.name,
            interval = domain.interval,
            endDate = domain.endDate
        )
    }
}
