package com.ledgerflow.domain.repository

import com.ledgerflow.domain.model.Attachment
import com.ledgerflow.domain.model.Result

interface AttachmentRepository {
    suspend fun saveAttachment(attachment: Attachment): Result<Unit>
    suspend fun deleteAttachment(attachmentId: Long): Result<Unit>
    suspend fun getAttachmentsForTransaction(transactionId: Long): Result<List<Attachment>>
}
