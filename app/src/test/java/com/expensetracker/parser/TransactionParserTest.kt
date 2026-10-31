package com.autoexpensetracker.parser

import com.autoexpensetracker.Checks
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

/**
 * Runs a corpus of bank and UPI alerts through the real parser with the real
 * bank_patterns.json. Two kinds of case: alerts that must become the right
 * transaction (direction, amount, merchant, balance) and look-alikes that must
 * NOT become one (OTPs, loan offers, balance updates, pre-debit notices).
 *
 * The alerts are written in the styles the banks use, from known templates, not
 * copied from real customers. When a real alert is parsed wrongly, add it to
 * [BankAlertCorpus] with the correct answer (mask account numbers and names).
 */
class TransactionParserTest {

    private fun loadConfig(): BankPatternConfig {
        val candidates = listOf(File("src/main/assets/bank_patterns.json"), File("app/src/main/assets/bank_patterns.json"))
        val file = candidates.firstOrNull { it.exists() } ?: error("bank_patterns.json not found from ${File(".").absolutePath}")
        return Json { ignoreUnknownKeys = true }.decodeFromString<BankPatternConfig>(file.readText())
    }

    @Test
    fun everyAlertInTheCorpusIsParsedAsExpected() {
        val parser = TransactionParser(loadConfig())
        val checks = Checks()
        for (c in BankAlertCorpus.ALL) {
            val problems = CorpusCheck.problems(parser, c)
            checks.check(if (problems.isEmpty()) c.name else "${c.name}: ${problems.joinToString("; ")}", problems.isEmpty())
        }
        checks.assertAll()
    }
}
