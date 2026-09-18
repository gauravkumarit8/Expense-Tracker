package com.autoexpensetracker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
enum class Direction { SENT, RECEIVED, UNKNOWN }

/**
 * Structured, parsed transaction record.
 *
 * IMPORTANT (see REQUIREMENTS.md Security ยง2 - Data Minimization):
 * We deliberately do NOT store the raw SMS/notification text here.
 * Only the fields extracted by TransactionParser are persisted.
 * `rawTextHash` is kept only for de-duplication (avoid double-counting the
 * same message if both the notification listener and SMS receiver fire for
 * it) and is a one-way hash, not reversible to the original text.
 *
 * `timestampMillis` is indexed (added in MIGRATION_4_5, see AppDatabase) —
 * flagged as an open item in REQUIREMENTS.md §7 ("Add CREATE INDEX
 * idx_transactions_timestampMillis if month/year filtering moves to a
 * DB-level query"). Month/year filtering (Charts, Monthly History) is
 * still done client-side over the full already-loaded list for now, not a
 * WHERE clause — that part of the open item is unchanged by this — but the
 * index is cheap to add now and ready for whenever that query-level change
 * happens, rather than needing a second migration then.
 */
@Serializable
@Entity(tableName = "transactions", indices = [Index(value = ["timestampMillis"])])
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val direction: Direction,
    val merchantOrContact: String?,   // best-effort extracted counterparty
    val bankOrSource: String,         // sender id, e.g. "HDFCBK"
    val timestampMillis: Long,        // taken from SMS/notification post time, not parsed from text
    val category: String? = null,     // Category enum name; auto-assigned at insert, user-overridable
    val note: String? = null,         // free-text note, user-entered only
    val tags: String? = null,         // comma-separated tags, user-entered only
    val balanceAfter: Double? = null, // account balance after this transaction, if the SMS/notification included it (e.g. "Avl Bal Rs.X"); null if not present in the message
    val rawTextHash: String,          // SHA-256 of original text, for dedup only
    val needsReview: Boolean = false  // true if parser had low confidence
)