package com.expensetracker.parser

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BankPatternEntry(
    val senderMatch: String,
    val debitedRegex: String,
    val creditedRegex: String
)

@Serializable
data class BankPatternConfig(
    val version: Int,
    val patterns: List<BankPatternEntry>,
    val excludeKeywords: List<String>,
    val transactionKeywords: List<String>
)

object BankPatternsLoader {
    private var cached: BankPatternConfig? = null

    fun load(context: Context): BankPatternConfig {
        cached?.let { return it }
        val text = context.assets.open("bank_patterns.json").bufferedReader().use { it.readText() }
        val config = Json { ignoreUnknownKeys = true }.decodeFromString<BankPatternConfig>(text)
        cached = config
        return config
    }
}
