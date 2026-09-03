package com.expensetracker.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.expensetracker.BuildConfig
import com.expensetracker.data.AppDatabase
import com.expensetracker.data.InsertOutcome
import com.expensetracker.parser.TransactionParser
import com.expensetracker.util.UnusualSpendDetector

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
        private const val TAG = "ParseAndStoreWorker"
    }

    override suspend fun doWork(): Result {
        val sender = inputData.getString(KEY_SENDER) ?: return Result.failure()
        val text = inputData.getString(KEY_TEXT) ?: return Result.failure()
        val timestamp = inputData.getLong(KEY_TIMESTAMP, System.currentTimeMillis())

        val parser = TransactionParser(applicationContext)
        val transaction = parser.parse(sender, text, timestamp) ?: return Result.success() // not a transaction, discard silently

        val dao = AppDatabase.getInstance(applicationContext).transactionDao()

        // Exact-hash dedup and cross-source duplicate detection now happen
        // atomically inside a single Room `@Transaction` (see
        // TransactionDao.insertIfNotDuplicate). Previously this was two
        // separate suspend calls (a check, then an insert), which raced when
        // two notifications for the same real payment — e.g. a bank SMS
        // alert and a UPI app's own notification — arrived close together:
        // both could pass the check before either had committed. See
        // REQUIREMENTS.md ยง2.15 amendment (2026-09-02).
        when (val outcome = dao.insertIfNotDuplicate(transaction)) {
            is InsertOutcome.Inserted -> {
                if (outcome.id > 0) {
                    UnusualSpendDetector.checkAndNotify(applicationContext, dao, transaction.copy(id = outcome.id))
                }
            }
            is InsertOutcome.ExactDuplicateSkipped -> {
                if (BuildConfig.DEBUG) Log.d(TAG, "Skipped exact-hash duplicate from $sender")
            }
            is InsertOutcome.CrossSourceDuplicateSkipped -> {
                if (BuildConfig.DEBUG) Log.d(TAG, "Skipped cross-source duplicate of transaction #${outcome.existingId} from $sender")
            }
        }
        // `text` and `sender` local vars go out of scope here and are not
        // referenced anywhere else — nothing raw is written to logs or disk.
        return Result.success()
    }
}