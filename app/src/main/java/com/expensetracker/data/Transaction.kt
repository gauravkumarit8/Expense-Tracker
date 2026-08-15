package com.expensetracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

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
 */
@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val direction: Direction,
    val merchantOrContact: String?,   // best-effort extracted counterparty
    val bankOrSource: String,         // sender id, e.g. "HDFCBK"
    val timestampMillis: Long,        // taken from SMS/notification post time, not parsed from text
    val category: String? = null,     // user-assigned or auto-categorized later
    val rawTextHash: String,          // SHA-256 of original text, for dedup only
    val needsReview: Boolean = false  // true if parser had low confidence
)
