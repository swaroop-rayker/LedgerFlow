package com.ledgerflow.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Attachment(
    val id: Long = 0,
    val transactionId: Long,
    val filePath: String,
    val fileType: String,
    val ocrData: String? = null
)
