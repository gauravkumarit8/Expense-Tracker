package com.autoexpensetracker.listener

/**
 * Whether a notification TITLE looks like an Indian bank/DLT SMS sender ID.
 *
 * Real sender IDs arrive as an optional 2-letter route prefix, a 6-character
 * header, and — very commonly — a 1-letter traffic suffix: "HDFCBK",
 * "AX-HDFCBK", "JD-SLCBNK-T", "CP-HDFCBN-S". The earlier pattern had no
 * suffix part, so every "-T"/"-S" form (which is most of them, including the
 * ones in this app's own captured data) failed the shape check. That only
 * mattered when the SMS app wasn't already trusted by package name, which is
 * exactly the situation on a phone whose SMS app isn't in the known list.
 *
 * Case-sensitive on purpose: real sender IDs are uppercase, and a contact
 * called "Mom" or "John Smith" must not look like a bank.
 */
object SenderShape {
    private val BANK_SENDER = Regex("^(?:[A-Z]{2}-)?[A-Z0-9]{6}(?:-[A-Z])?$")

    fun looksLikeBankSender(title: String): Boolean = BANK_SENDER.matches(title.trim())
}
