package com.ledgerflow.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ledgerflow.domain.model.Attachment

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transaction_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["transaction_id"], name = "idx_attachments_transaction")
    ]
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "transaction_id") val transactionId: Long,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "file_type") val fileType: String,
    @ColumnInfo(name = "ocr_data") val ocrData: String?
) {
    fun toDomain() = Attachment(
        id = id,
        transactionId = transactionId,
        filePath = filePath,
        fileType = fileType,
        ocrData = ocrData
    )

    companion object {
        fun fromDomain(domain: Attachment) = AttachmentEntity(
            id = domain.id,
            transactionId = domain.transactionId,
            filePath = domain.filePath,
            fileType = domain.fileType,
            ocrData = domain.ocrData
        )
    }
}
