package com.autoexpensetracker.backup

import com.autoexpensetracker.data.Budget
import com.autoexpensetracker.data.Reminder
import com.autoexpensetracker.data.Transaction
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Full local backup of user data. NOT encrypted — the user chooses the save
 * location via the system file picker (Storage Access Framework), so this
 * relies on the user picking somewhere private. See REQUIREMENTS.md ยง2.10
 * for the full tradeoff discussion and the "encrypt the export file" Open
 * Item.
 */
@Serializable
data class BackupPayload(
    val formatVersion: Int = 1,
    val exportedAtMillis: Long,
    val transactions: List<Transaction>,
    val reminders: List<Reminder>,
    val budgets: List<Budget>
)

object BackupSerializer {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun toJson(payload: BackupPayload): String = json.encodeToString(BackupPayload.serializer(), payload)

    fun fromJson(text: String): BackupPayload = json.decodeFromString(BackupPayload.serializer(), text)
}