package com.ledgerflow.data.repository

import com.ledgerflow.data.db.dao.AttachmentDao
import com.ledgerflow.data.db.entity.AttachmentEntity
import com.ledgerflow.domain.model.Attachment
import com.ledgerflow.domain.model.Result
import com.ledgerflow.domain.repository.AttachmentRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class AttachmentRepositoryImpl @Inject constructor(
    private val attachmentDao: AttachmentDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AttachmentRepository {

    override suspend fun saveAttachment(attachment: Attachment): Result<Unit> = withContext(ioDispatcher) {
        try {
            attachmentDao.insertAttachment(AttachmentEntity.fromDomain(attachment))
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun deleteAttachment(attachmentId: Long): Result<Unit> = withContext(ioDispatcher) {
        try {
            attachmentDao.deleteAttachment(attachmentId)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }

    override suspend fun getAttachmentsForTransaction(transactionId: Long): Result<List<Attachment>> = withContext(ioDispatcher) {
        try {
            val entities = attachmentDao.getAttachmentsForTransaction(transactionId)
            Result.Success(entities.map { it.toDomain() })
        } catch (e: Exception) {
            Result.Failure.DatabaseError(e)
        }
    }
}
