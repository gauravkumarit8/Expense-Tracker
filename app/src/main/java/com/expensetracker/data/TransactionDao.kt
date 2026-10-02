package com.autoexpensetracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction as RoomTransaction
import androidx.room.Update
import com.autoexpensetracker.util.DuplicateDetector
import kotlinx.coroutines.flow.Flow

/**
 * Outcome of [TransactionDao.insertIfNotDuplicate]. See REQUIREMENTS.md
 * ยง2.15 amendment (2026-09-02) for why this replaced a separate
 * check-then-insert call pair from the worker.
 */
sealed class InsertOutcome {
    data class Inserted(val id: Long) : InsertOutcome()
    object ExactDuplicateSkipped : InsertOutcome()
    data class CrossSourceDuplicateSkipped(val existingId: Long) : InsertOutcome()
}

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

    // 2026-09-28: this used to match on hash alone, with no time bound, so
    // an exact repeat of the SAME text was dropped forever no matter how
    // much time had passed. That's correct for a re-posted/updated
    // notification of the same event (the common case this exists for),
    // but a genuinely separate later transaction with identical text (a
    // generic app notification with no amount/ref/timestamp baked in, at
    // the same merchant for the same amount) would be silently discarded
    // too. Bounded to a window so only near-in-time repeats are treated as
    // the same notification.
    @Query("SELECT COUNT(*) FROM transactions WHERE rawTextHash = :hash AND timestampMillis BETWEEN :start AND :end")
    suspend fun existsByHashNear(hash: String, start: Long, end: Long): Int

    /**
     * Narrow window query backing the cross-source duplicate check — only
     * pulls rows within the dedup window instead of the whole table, unlike
     * the previous `getAllOnce().filter { ... }` approach in
     * ParseAndStoreWorker.
     */
    @Query("SELECT * FROM transactions WHERE timestampMillis BETWEEN :start AND :end")
    suspend fun getNearTimestamp(start: Long, end: Long): List<Transaction>

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    /**
     * Atomically checks for an exact-hash duplicate and a likely
     * cross-source duplicate, then inserts — all within one Room
     * `@Transaction`. This exists specifically to close a race condition:
     * doing the check and the insert as two separate suspend calls (the
     * original design) let two `ParseAndStoreWorker` instances running
     * concurrently — e.g. a bank SMS notification and a UPI app's own
     * notification for the same real payment, landing milliseconds apart —
     * both pass the "does a duplicate exist yet?" check before either had
     * committed its insert, so both rows landed in the DB.
     *
     * Room routes `@Transaction` suspend functions through its internal
     * transaction executor, which serializes concurrent callers against the
     * same database connection, so this closes the gap regardless of which
     * side (bank SMS vs UPI app notification) happens to arrive first — the
     * matching criteria in [DuplicateDetector] were already order-
     * independent; the race was the actual bug. See REQUIREMENTS.md ยง2.15
     * amendment (2026-09-02).
     */
    @RoomTransaction
    suspend fun insertIfNotDuplicate(
        transaction: Transaction,
        duplicateWindowMillis: Long = 90_000L,
        // Deliberately much larger than duplicateWindowMillis: this covers
        // a notification being re-posted/updated by the OS or the source
        // app well after the original 90s cross-source window, which is
        // sized for a DIFFERENT event (two apps reporting one real-world
        // payment) arriving close together, not for how late a repost of
        // the exact same notification can land.
        exactHashWindowMillis: Long = 24 * 60 * 60 * 1000L
    ): InsertOutcome {
        if (existsByHashNear(
                transaction.rawTextHash,
                transaction.timestampMillis - exactHashWindowMillis,
                transaction.timestampMillis + exactHashWindowMillis
            ) > 0
        ) {
            return InsertOutcome.ExactDuplicateSkipped
        }

        if (transaction.bankOrSource != "Cash") {
            val nearby = getNearTimestamp(
                transaction.timestampMillis - duplicateWindowMillis,
                transaction.timestampMillis + duplicateWindowMillis
            )
            val duplicateOf = DuplicateDetector.findDuplicate(transaction, nearby)
            if (duplicateOf != null) {
                return InsertOutcome.CrossSourceDuplicateSkipped(duplicateOf.id)
            }
        }

        val id = insert(transaction)
        return InsertOutcome.Inserted(id)
    }
}