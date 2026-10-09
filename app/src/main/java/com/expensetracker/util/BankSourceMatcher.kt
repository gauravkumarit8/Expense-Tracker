package com.autoexpensetracker.util

/**
 * Indian bank SMS sender IDs look like `XX-BANKCODE-T`: a 2-letter route /
 * telecom-circle prefix, the bank's header code, and a 1-letter traffic
 * suffix (T = transactional, S = service, P = promotional, G = government).
 * The SAME bank routinely arrives under different prefixes and suffixes —
 * e.g. "JD-SLCBNK-T" and "JK-SLCBNK-T" (or "CP-HDFCBN-S" and "VM-HDFCBN-T")
 * — depending on which route the carrier happened to use.
 *
 * The "Account balances" list used to group by the exact sender string, so
 * one real account showed up as two cards with near-identical balances.
 * [accountKey] collapses those variants to the bank code so they group
 * together.
 *
 * Deliberately conservative:
 *  - The pattern is case-SENSITIVE and fully uppercase, because real sender
 *    IDs are. A name the user typed by hand ("My-Bank") therefore does NOT
 *    get its prefix stripped — otherwise "My-Bank" and "Other-Bank" would
 *    both collapse to "BANK" and silently merge two different accounts.
 *  - Anything that isn't sender-ID-shaped is left as-is (only upper-cased
 *    and trimmed, so "hdfc" and "HDFC" compare equal).
 *  - Different bank codes never merge ("SLCBNK" vs "CENTBK").
 *
 * Known limitation (pre-existing, not introduced here): a sender ID doesn't
 * identify WHICH account at a bank. Two separate accounts at the same bank
 * arriving under the same sender code are one card, as they were before.
 */
object BankSourceMatcher {

    private val SENDER_ID = Regex("^[A-Z]{2}-([A-Z0-9]{3,})(?:-[A-Z])?$")

    fun accountKey(source: String): String {
        val trimmed = source.trim()
        return SENDER_ID.matchEntire(trimmed)?.groupValues?.get(1) ?: trimmed.uppercase()
    }
}
