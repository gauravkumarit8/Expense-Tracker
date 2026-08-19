package com.expensetracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(transaction: Transaction): Long

    @Update
    suspend fun update(transaction: Transaction)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM transactions ORDER BY timestampMillis DESC")
    fun getAll(): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions")
    suspend fun getAllOnce(): List<Transaction>

    @Query("SELECT * FROM transactions WHERE needsReview = 1 ORDER BY timestampMillis DESC")
    fun getNeedsReview(): Flow<List<Transaction>>

    @Query("SELECT COUNT(*) FROM transactions WHERE rawTextHash = :hash")
    suspend fun existsByHash(hash: String): Int

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}