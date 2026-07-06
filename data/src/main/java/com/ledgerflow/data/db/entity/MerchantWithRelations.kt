package com.ledgerflow.data.db.entity

import androidx.room.Embedded
import androidx.room.Relation

data class MerchantWithRelations(
    @Embedded val merchant: MerchantEntity,
    
    @Relation(
        parentColumn = "id",
        entityColumn = "merchant_id"
    )
    val aliases: List<MerchantAliasEntity> = emptyList(),

    @Relation(
        parentColumn = "id",
        entityColumn = "merchant_id"
    )
    val regexes: List<MerchantRegexEntity> = emptyList()
) {
    fun toDomain(): com.ledgerflow.domain.model.Merchant {
        return merchant.toDomain(
            aliases = aliases.map { it.alias },
            regexPatterns = regexes.map { it.pattern }
        )
    }
}
