package com.ledgerflow.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ledgerflow.domain.model.PaymentMethod
import com.ledgerflow.domain.model.PaymentMethodType

@Entity(
    tableName = "payment_methods",
    indices = [
        Index(value = ["name"], unique = true, name = "idx_payment_methods_name")
    ]
)
data class PaymentMethodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String
) {
    fun toDomain() = PaymentMethod(
        id = id,
        name = name,
        type = PaymentMethodType.valueOf(type)
    )

    companion object {
        fun fromDomain(domain: PaymentMethod) = PaymentMethodEntity(
            id = domain.id,
            name = domain.name,
            type = domain.type.name
        )
    }
}
