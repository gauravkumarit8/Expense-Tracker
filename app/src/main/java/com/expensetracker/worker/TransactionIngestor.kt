package com.autoexpensetracker.worker

import android.content.Context
import android.util.Log
import com.autoexpensetracker.BuildConfig
import com.autoexpensetracker.data.AppDatabase
import com.autoexpensetracker.data.InsertOutcome
import com.autoexpensetracker.parser.TransactionParser
import com.autoexpensetracker.review.ReviewPromptStore
import com.autoexpensetracker.util.UnusualSpendDetector
import com.autoexpensetracker.util.BudgetWarningDetector
import com.autoexpensetracker.util.SubscriptionPriceChangeDetector
import com.autoexpensetracker.util.PriceChangeStore
import com.autoexpensetracker.util.PriceChangeNotificationHelper
import com.autoexpensetracker.widget.WidgetRefresher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Parses a captured notification and stores the resulting structured
 * Transaction in the encrypted Room DB.
 *
 * 2026-09-28: the notification listener used to hand the FULL raw message
 * text to WorkManager via input Data. WorkManager persists that in its own
 * plain, unencrypted SQLite database, which contradicted both the
 * "nothing raw is written to disk" comment in ParseAndStoreWorker and the
 * app's "all financial data stays encrypted" claim (raw bank SMS text
 * contains account digits, balances and counterparty names). Parsing now
 * happens here, in memory, on a background thread; only the parsed
 * Transaction is ever written, and only into the SQLCipher database.
 *
 * Uses a process-wide scope (not the service's lifecycle) so an in-flight
 * capture isn't cancelled if the system rebinds/destroys the listener.
 */
object TransactionIngestor {

    private const val TAG = "TransactionIngestor"
    private const val MAX_ATTEMPTS = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fire-and-forget entry point for the notification listener. */
    fun enqueue(context: Context, sender: String, text: String, timestampMillis: Long) {
        val appContext = context.applicationContext
        scope.launch {
            // A transient failure (e.g. DB busy) is retried a couple of
            // times, replacing the retry WorkManager used to provide.
            repeat(MAX_ATTEMPTS) { attempt ->
                try {
                    ingest(appContext, sender, text, timestampMillis)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Ingest attempt ${attempt + 1} failed", e)
                    if (attempt < MAX_ATTEMPTS - 1) delay(2_000L * (attempt + 1))
                }
            }
        }
    }

    /** Parse + dedup + insert. Throws on unexpected failure so callers can retry. */
    suspend fun ingest(context: Context, sender: String, text: String, timestampMillis: Long) {
        val appContext = context.applicationContext
        val transaction = TransactionParser(appContext).parse(sender, text, timestampMillis)
            ?: return // not a transaction, discard silently

        val dao = AppDatabase.getInstance(appContext).transactionDao()

        // Exact-hash dedup and cross-source duplicate detection happen
        // atomically inside one Room @Transaction (see
        // TransactionDao.insertIfNotDuplicate).
        when (val outcome = dao.insertIfNotDuplicate(transaction)) {
            is InsertOutcome.Inserted -> {
                if (outcome.id > 0) {
                    val inserted = transaction.copy(id = outcome.id)
                    UnusualSpendDetector.checkAndNotify(appContext, dao, inserted)
                    BudgetWarningDetector.checkAndNotify(appContext, dao, AppDatabase.getInstance(appContext).budgetDao(), inserted)
                    // Only act on an alert that's actually ABOUT this new
                    // transaction (detect() re-scans everything every time,
                    // so most calls return alerts for merchants unrelated
                    // to what was just inserted).
                    SubscriptionPriceChangeDetector.detect(dao.getAllOnce())
                        .firstOrNull { it.newTransactionId == inserted.id }
                        ?.let { alert ->
                            if (!PriceChangeStore.isHandled(appContext, alert.merchant, alert.newAmount)) {
                                PriceChangeStore.markHandled(appContext, alert.merchant, alert.newAmount)
                                PriceChangeNotificationHelper.show(appContext, alert)
                            }
                        }
                    ReviewPromptStore.recordCapturedTransaction(appContext)
                    WidgetRefresher.refresh(appContext)
                }
            }
            is InsertOutcome.ExactDuplicateSkipped -> {
                if (BuildConfig.DEBUG) Log.d(TAG, "Skipped exact-hash duplicate from $sender")
            }
            is InsertOutcome.CrossSourceDuplicateSkipped -> {
                if (BuildConfig.DEBUG) Log.d(TAG, "Skipped cross-source duplicate of transaction #${outcome.existingId} from $sender")
            }
        }
        // `text` and `sender` are not retained or logged beyond this call.
    }
}
