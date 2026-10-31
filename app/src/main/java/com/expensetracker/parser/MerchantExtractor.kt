package com.autoexpensetracker.parser

/**
 * Finds who a payment was to / from when the per-bank pattern in
 * bank_patterns.json did not give a usable name. Pure Kotlin, no Android.
 *
 * Why it exists: most banks put the counterparty somewhere the per-bank patterns
 * don't look - inside a UPI reference ("UPI/P2M/429387461234/SWIGGY",
 * "Info: UPI-429387461234-ZOMATO"), as a UPI ID ("to swiggy@icici"), after
 * "trf to", after "towards", or after a semicolon ("debited for Rs 500; SWIGGY
 * credited"). Without it those alerts show as "Unknown", which is a poor first
 * impression and defeats categorisation.
 *
 * It only runs when the existing pattern produced nothing, or something that
 * is clearly not a name (a helpline number taken from "SMS BLOCK 123 to
 * 9215676766", say), so banks whose patterns already work are not affected.
 */
object MerchantExtractor {

    private const val NAME = "([A-Za-z][A-Za-z0-9 &'_.-]{1,38}?)"

    // A name ends at a full stop, slash, comma, semicolon, line break, end of text,
    // or the word that starts the next part of the message.
    private const val END = "(?=\\s*(?:[./,;\\n]|\\s(?:avl|avail|bal|balance|not|ref|refno|if|sms|call|on|via|using|upi|from|to)\\b|\$))"

    private val UPI_REF = Regex("(?i)\\bupi[/-](?:p2[mpa][/-])?\\d{8,}[/-]" + NAME + END)
    private val UPI_ID = Regex("(?i)\\b(?:to|from|by|vpa)\\s+(?:vpa\\s+)?([a-z0-9._-]{2,}@[a-z]{2,})")
    private val TRANSFER_PARTY = Regex("(?i)\\b(?:trf|transfer|transferred)\\s+(?:to|from)\\s+" + NAME + END)
    private val SEMICOLON_PARTY = Regex("(?i);\\s*" + NAME + "\\s+credited")
    private val TOWARDS = Regex("(?i)\\btowards\\s+" + NAME + END)
    private val AT_MERCHANT = Regex("(?i)\\bat\\s+" + NAME + END)

    // Words that start a boilerplate sentence, never a merchant.
    private val JUNK_START = listOf(
        "block", "call", "dispute", "sms", "ref", "refno", "your", "you", "a/c", "ac", "acct", "account",
        "not", "if", "avl", "bal", "balance", "available", "customer", "dear", "bank", "the bank"
    )

    /** True when [name] is missing or clearly not a counterparty: a phone or reference number, or boilerplate. */
    fun isJunk(name: String?): Boolean {
        if (name == null) return true
        val t = name.trim()
        if (t.length < 2) return true
        val digits = t.count { it.isDigit() }
        val letters = t.count { it.isLetter() }
        if (letters == 0) return true
        if (digits >= 6 && digits * 2 >= t.length) return true
        val lower = t.lowercase()
        return JUNK_START.any { lower == it || lower.startsWith("$it ") }
    }

    /** The first usable counterparty found in [text], or null. */
    fun extract(text: String): String? {
        val flat = text.replace("\r", "\n")
        val candidates = listOf(
            UPI_REF.find(flat)?.groupValues?.get(1),
            UPI_ID.find(flat)?.groupValues?.get(1),
            TRANSFER_PARTY.find(flat)?.groupValues?.get(1),
            SEMICOLON_PARTY.find(flat)?.groupValues?.get(1),
            TOWARDS.find(flat)?.groupValues?.get(1),
            AT_MERCHANT.find(flat)?.groupValues?.get(1)
        )
        for (c in candidates) {
            val cleaned = clean(c)
            if (cleaned != null && !isJunk(cleaned)) return cleaned
        }
        return null
    }

    /** [fromPattern] if it is a real name, otherwise whatever [extract] finds. */
    fun resolve(fromPattern: String?, text: String): String? =
        if (!isJunk(fromPattern)) fromPattern else extract(text)

    private fun clean(raw: String?): String? {
        if (raw == null) return null
        val t = raw.trim().trimEnd('.', '-', '_', ' ').replace(Regex("\\s+"), " ")
        return if (t.isEmpty()) null else t
    }
}
