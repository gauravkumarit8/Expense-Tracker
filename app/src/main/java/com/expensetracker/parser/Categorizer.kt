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
            "dominos", "mcdonald", "kfc", "starbucks", "eatery", "dine"
        ),
        Category.GROCERIES to listOf(
            "bigbasket", "grofers", "blinkit", "zepto", "dmart", "grocery",
            "supermarket", "reliance fresh", "more supermarket", "instamart"
        ),
        Category.SHOPPING to listOf(
            "amazon", "flipkart", "myntra", "ajio", "nykaa", "meesho",
            "mall", "store", "shop", "retail"
        ),
        Category.BILLS to listOf(
            "electricity", "recharge", "broadband", "wifi", "postpaid",
            "prepaid", "gas bill", "water bill", "bill payment", "airtel",
            "jio", "vodafone", "vi ", "insurance", "premium", "emi", "loan"
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
        Category.TRANSFER to listOf(
            "upi", "transfer", "sent to", "received from"
        )
    )

    fun categorize(merchant: String?, rawText: String): Category {
        val merchantLower = merchant?.lowercase().orEmpty()
        val textLower = rawText.lowercase()

        // Check merchant name first — more reliable signal than the full message.
        for ((category, keywords) in rules) {
            if (keywords.any { merchantLower.contains(it) }) return category
        }
        // Fall back to scanning the full message text.
        for ((category, keywords) in rules) {
            if (keywords.any { textLower.contains(it) }) return category
        }
        return Category.OTHER
    }
}