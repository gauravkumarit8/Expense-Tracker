package com.autoexpensetracker.parser

import com.autoexpensetracker.data.Category

/**
 * Lightweight keyword-based auto-categorization. Runs against the extracted
 * merchant name first (more reliable), falling back to the raw message text.
 * Deliberately simple (no ML/network call) to stay consistent with the
 * project's "lightweight, on-device, explainable" design goals — see
 * REQUIREMENTS.md ยง2.2.
 *
 * This is a starting keyword set, not exhaustive. Users can always override
 * the auto-assigned category by hand in the UI (see MainActivity's
 * TransactionDetailSheet), and manual overrides always take priority — this
 * function is only ever called at insert time for a fresh, uncategorized row.
 */
object Categorizer {

    private val rules: List<Pair<Category, List<String>>> = listOf(
        Category.FOOD to listOf(
            "swiggy", "zomato", "restaurant", "cafe", "food", "pizza", "burger",
            "dominos", "mcdonald", "kfc", "starbucks", "eatery", "dine", "foodpanda"
        ),
        Category.GROCERIES to listOf(
            "bigbasket", "grofers", "blinkit", "zepto", "dmart", "grocery",
            "supermarket", "reliance fresh", "more supermarket", "instamart", "jiomart"
        ),
        Category.SHOPPING to listOf(
            "amazon", "flipkart", "myntra", "ajio", "nykaa", "meesho",
            "mall", "store", "shop", "retail"
        ),
        Category.BILLS to listOf(
            "electricity", "recharge", "broadband", "wifi", "postpaid",
            "prepaid", "gas bill", "water bill", "bill payment", "airtel",
            "jio", "jiofiber", "vodafone", "vi ", "insurance", "premium", "emi", "loan"
        ),
        Category.ENTERTAINMENT to listOf(
            "netflix", "prime video", "hotstar", "spotify", "bookmyshow",
            "pvr", "inox", "cinema", "movie", "gaana", "youtube premium"
        ),
        Category.TRAVEL to listOf(
            "uber", "ola", "rapido", "irctc", "railway", "airlines", "indigo",
            "spicejet", "makemytrip", "goibibo", "redbus", "flight", "cab",
            "petrol", "fuel", "toll", "fastag"
        ),
        Category.HEALTH to listOf(
            "pharmacy", "apollo", "hospital", "clinic", "medplus", "netmeds",
            "1mg", "diagnostic", "medical", "doctor"
        ),
        // Only explicit movement-of-money wording. This used to be "upi",
        // "sent to" and "received from", which appear in nearly every UPI and
        // bank message, so any payment to a merchant the keyword lists above
        // did not know became a "Transfer" - and one large payment then swamped
        // the month's totals and budgets. An unrecognised payment is now
        // Category.OTHER (uncategorised), which the user can change by hand,
        // rather than a confident wrong label.
        Category.TRANSFER to listOf(
            "transfer", "neft", "imps", "rtgs", "own account"
        )
    )

    /**
     * Keywords of four characters or fewer must match a WHOLE word. As plain
     * substrings they fired inside ordinary names: "vi " in "Ravi Kumar"
     * (Bills), "dine" in "Dinesh" (Food), "ola" in "Coca-Cola" (Travel),
     * "mall" in "Small". Longer keywords stay substring matches, so
     * "restaurant" still finds "Restaurants Pvt Ltd".
     */
    private fun matches(text: String, keyword: String): Boolean {
        val k = keyword.trim()
        if (k.length > MAX_WHOLE_WORD_LENGTH) return text.contains(keyword)
        var from = 0
        while (true) {
            val i = text.indexOf(k, from)
            if (i < 0) return false
            val before = if (i == 0) ' ' else text[i - 1]
            val afterIndex = i + k.length
            val after = if (afterIndex >= text.length) ' ' else text[afterIndex]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            from = i + 1
        }
    }

    private const val MAX_WHOLE_WORD_LENGTH = 4

    fun categorize(merchant: String?, rawText: String): Category {
        val merchantLower = merchant?.lowercase().orEmpty()
        val textLower = rawText.lowercase()

        // Check merchant name first — more reliable signal than the full message.
        for ((category, keywords) in rules) {
            if (keywords.any { matches(merchantLower, it) }) return category
        }
        // Fall back to scanning the full message text.
        for ((category, keywords) in rules) {
            if (keywords.any { matches(textLower, it) }) return category
        }
        return Category.OTHER
    }
}