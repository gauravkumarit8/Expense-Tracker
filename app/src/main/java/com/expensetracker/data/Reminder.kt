package com.autoexpensetracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,           // e.g. "Netflix", "Electricity bill"
    val amount: Double?,         // optional — some bills vary month to month
    val dueDayOfMonth: Int,      // 1-31; days beyond a short month roll to month-end
    val notes: String? = null,
    val lastNotifiedYearMonth: String? = null // "2026-08", prevents duplicate same-day notifications
)