package com.ledgerflow.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ledgerflow.domain.model.Transaction
import com.ledgerflow.domain.model.TransactionType

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = MerchantEntity::class,
            parentColumns = ["id"],
            childColumns = ["merchant_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = PaymentMethodEntity::class,
            parentColumns = ["id"],
            childColumns = ["payment_method_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = RecurringRuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["recurring_rule_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["timestamp"], name = "idx_transactions_timestamp"),
        Index(value = ["merchant_id"], name = "idx_transactions_merchant"),
        Index(value = ["payment_method_id"], name = "idx_transactions_payment_method"),
        Index(value = ["recurring_rule_id"], name = "idx_transactions_recurring_rule")
    ]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    @ColumnInfo(name = "total_amount") val totalAmount: Long,
    val type: String,
    @ColumnInfo(name = "merchant_id") val merchantId: Long?,
    @ColumnInfo(name = "payment_method_id") val paymentMethodId: Long?,
    val notes: String?,
    @ColumnInfo(name = "is_recurring") val isRecurring: Boolean,
    @ColumnInfo(name = "recurring_rule_id") val recurringRuleId: Long?
) {
    fun toDomain() = Transaction(
        id = id,
        timestamp = timestamp,
        totalAmount = totalAmount,
        type = TransactionType.valueOf(type),
        merchantId = merchantId,
        paymentMethodId = paymentMethodId,
        notes = notes,
        isRecurring = isRecurring,
        recurringRuleId = recurringRuleId
    )

    companion object {
        fun fromDomain(domain: Transaction) = TransactionEntity(
            id = domain.id,
            timestamp = domain.timestamp,
            totalAmount = domain.totalAmount,
            type = domain.type.name,
            merchantId = domain.merchantId,
            paymentMethodId = domain.paymentMethodId,
            notes = domain.notes,
            isRecurring = domain.isRecurring,
            recurringRuleId = domain.recurringRuleId
        )
    }
}
