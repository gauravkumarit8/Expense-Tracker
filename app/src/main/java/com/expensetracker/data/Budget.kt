package com.expensetracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "budgets")
data class Budget(
    @PrimaryKey val category: String, // Category enum name, one budget per category
    val monthlyLimit: Double
)