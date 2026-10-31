package com.autoexpensetracker.util

/**
 * Turns a raw bank sender code ("JK-CENTBK-T", "HDFCBK", "VM-SBIINB-S") into a
 * name a person recognises ("Central Bank of India", "HDFC Bank", "State Bank
 * of India"). DISPLAY ONLY: what is stored, exported (CSV/PDF/backup) and used
 * to group accounts stays the raw sender code, so nothing about matching,
 * de-duplication or CSV round trips changes.
 *
 * Best effort, and deliberately conservative:
 *  - Only the bank codes the parser already recognises (bank_patterns.json) are
 *    named. An unknown code is returned exactly as it came in. A wrong name is
 *    worse than a code.
 *  - Only a code-shaped value (letters and digits, no spaces) is renamed, so a
 *    label the user typed ("SBI Savings", "Cash", "Mom's wallet") is never
 *    replaced.
 *  - Matching is by prefix on the bank code, most specific first ("SBICRD" is
 *    SBI Card, not State Bank of India).
 */
object BankNames {

    // Order matters: the first prefix that matches wins.
    private val PREFIXES = listOf(
        "SBICRD" to "SBI Card",
        "SBI" to "State Bank of India",
        "HDFC" to "HDFC Bank",
        "ICICI" to "ICICI Bank",
        "KOTAK" to "Kotak Mahindra Bank",
        "AXIS" to "Axis Bank",
        "SLCBNK" to "Slice",
        "YESB" to "Yes Bank",
        "IDFC" to "IDFC FIRST Bank",
        "PNB" to "Punjab National Bank",
        "BOBTXN" to "Bank of Baroda",
        "BOBBNK" to "Bank of Baroda",
        "CANBNK" to "Canara Bank",
        "CANARA" to "Canara Bank",
        "INDUS" to "IndusInd Bank",
        "RBLBNK" to "RBL Bank",
        "FEDBNK" to "Federal Bank",
        "IDBI" to "IDBI Bank",
        "BOIIND" to "Bank of India",
        "CBIBNK" to "Central Bank of India",
        "CENTBK" to "Central Bank of India",
        "INDBNK" to "Indian Bank",
        "UCO" to "UCO Bank",
        "SCBANK" to "Standard Chartered",
        "CITI" to "Citi Bank",
        "HSBC" to "HSBC",
        "AMEX" to "American Express",
        "DBSBNK" to "DBS Bank",
        "AUBANK" to "AU Small Finance Bank",
        "BANDHN" to "Bandhan Bank",
        "CREDCB" to "CRED",
        "UNION" to "Union Bank of India"
    )

    private val CODE_SHAPED = Regex("^[A-Z0-9]+$")

    fun friendly(source: String): String {
        val key = BankSourceMatcher.accountKey(source)
        if (!CODE_SHAPED.matches(key)) return source
        for ((prefix, name) in PREFIXES) {
            if (key.startsWith(prefix)) return name
        }
        return source
    }
}
