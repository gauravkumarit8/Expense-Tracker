package com.expensetracker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.expensetracker.data.AppDatabase
import com.expensetracker.parser.TransactionParser

/**
 * Runs off the main/callback thread. Parses the raw text, discards it, and
 * persists only the structured Transaction to the encrypted Room DB.
 * See REQUIREMENTS.md Security ยง2 (Data Minimization) — `text` never
 * outlives this function call.
 */
class ParseAndStoreWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_SENDER = "sender"
        const val KEY_TEXT = "text"
        const val KEY_TIMESTAMP = "timestamp"
    }

    override suspend fun doWork(): Result {
        val sender = inputData.getString(KEY_SENDER) ?: return Result.failure()
        val text = inputData.getString(KEY_TEXT) ?: return Result.failure()
        val timestamp = inputData.getLong(KEY_TIMESTAMP, System.currentTimeMillis())

        val parser = TransactionParser(applicationContext)
        val transaction = parser.parse(sender, text, timestamp) ?: return Result.success() // not a transaction, discard silently

        val dao = AppDatabase.getInstance(applicationContext).transactionDao()
        if (dao.existsByHash(transaction.rawTextHash) == 0) {
            dao.insert(transaction)
        }
        // `text` and `sender` local vars go out of scope here and are not
        // referenced anywhere else — nothing raw is written to logs or disk.
        return Result.success()
    }
}
