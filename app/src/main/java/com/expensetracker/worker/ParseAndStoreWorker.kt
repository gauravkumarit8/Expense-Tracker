package com.autoexpensetracker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * LEGACY. The notification listener no longer enqueues this worker (see
 * [TransactionIngestor] for why: WorkManager stored the raw message text
 * in its unencrypted database). The class is deliberately kept so that any
 * job persisted by an older app version, still waiting in WorkManager's
 * queue at the moment of an update, can still be instantiated and run
 * instead of failing with ClassNotFoundException. It can be deleted once
 * no installed version older than this one remains in the field.
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
        TransactionIngestor.ingest(applicationContext, sender, text, timestamp)
        return Result.success()
    }
}
