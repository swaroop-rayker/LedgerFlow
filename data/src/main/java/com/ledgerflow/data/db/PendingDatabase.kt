package com.ledgerflow.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.ledgerflow.data.db.dao.PendingTransactionDao
import com.ledgerflow.data.db.entity.PendingTransactionEntity

@Database(
    entities = [PendingTransactionEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PendingDatabase : RoomDatabase() {
    abstract fun pendingTransactionDao(): PendingTransactionDao
}
