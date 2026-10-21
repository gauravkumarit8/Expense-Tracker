package com.autoexpensetracker.util

import com.autoexpensetracker.Checks
import org.junit.Test
import com.autoexpensetracker.util.SearchLogic as S

class SearchLogicTest {
    private val checks = Checks()
    private fun check(n: String, ok: Boolean) = checks.check(n, ok)

    @Test
    fun allChecks() {
    // amounts that must parse
    check("340", S.parseAmountQuery("340") == 340.0)
    check("rupee", S.parseAmountQuery("₹340") == 340.0)
    check("rupee space", S.parseAmountQuery("₹ 340") == 340.0)
    check("Rs. 1,500", S.parseAmountQuery("Rs. 1,500") == 1500.0)
    check("rs lower no dot", S.parseAmountQuery("rs 1500") == 1500.0)
    check("RS.1500", S.parseAmountQuery("RS.1500") == 1500.0)
    check("inr", S.parseAmountQuery("INR 2,000") == 2000.0)
    check("indian", S.parseAmountQuery("1,50,000") == 150000.0)
    check("indian big", S.parseAmountQuery("12,34,567") == 1234567.0)
    check("western", S.parseAmountQuery("1,500,000") == 1500000.0)
    check("western 123,456", S.parseAmountQuery("123,456") == 123456.0)
    check("decimal", S.parseAmountQuery("99.50") == 99.5)
    check("grouped decimal", S.parseAmountQuery("₹1,500.25") == 1500.25)
    check("padding", S.parseAmountQuery("  340  ") == 340.0)
    check("2 digit grouped ok", S.parseAmountQuery("12,345") == 12345.0)
    // must not parse
    check("1,2,3", S.parseAmountQuery("1,2,3") == null)
    check("12,34", S.parseAmountQuery("12,34") == null)
    check("1,5000", S.parseAmountQuery("1,5000") == null)
    check("1,50,00", S.parseAmountQuery("1,50,00") == null)
    check(",500", S.parseAmountQuery(",500") == null)
    check("500,", S.parseAmountQuery("500,") == null)
    check("1,,500", S.parseAmountQuery("1,,500") == null)
    check("340.", S.parseAmountQuery("340.") == null)
    check("3.456", S.parseAmountQuery("3.456") == null)
    check("1.2.3", S.parseAmountQuery("1.2.3") == null)
    check("empty", S.parseAmountQuery("") == null)
    check("text", S.parseAmountQuery("swiggy") == null)
    check("rs alone", S.parseAmountQuery("rs") == null)
    check("rupee alone", S.parseAmountQuery("₹") == null)
    check("negative", S.parseAmountQuery("-340") == null)
    check("340 food", S.parseAmountQuery("340 food") == null)
    // exact match
    check("exact", S.amountMatches(340.0, 340.0))
    check("not contains 1340", !S.amountMatches(1340.0, 340.0))
    check("not contains 3400", !S.amountMatches(3400.0, 340.0))
    check("paise exact", S.amountMatches(99.5, 99.50))
    check("paise differ", !S.amountMatches(99.5, 99.0))
    check("float noise", S.amountMatches(0.1 + 0.2, 0.3))
    // category
    check("cat name", S.categoryMatches("food", "FOOD", "Food & Dining"))
    check("cat label part", S.categoryMatches("dining", "FOOD", "Food & Dining"))
    check("cat 1 char no", !S.categoryMatches("f", "FOOD", "Food & Dining"))
    check("cat none", !S.categoryMatches("travel", "FOOD", "Food & Dining"))
    check("cat null", !S.categoryMatches("food", null, null))
    check("cat trimmed", S.categoryMatches("  food ", "FOOD", null))
    // normalize
    check("norm", S.normalizeQuery("  swiggy   order \n") == "swiggy order")
    // recent
    check("add first", S.addRecent(emptyList(), "Swiggy") == listOf("Swiggy"))
    check("add newest first", S.addRecent(listOf("A1"), "B2") == listOf("B2", "A1"))
    check("dedupe case", S.addRecent(listOf("Swiggy", "Zomato"), "swiggy") == listOf("swiggy", "Zomato"))
    check("dedupe moves top", S.addRecent(listOf("a1", "b2", "c3"), "C3") == listOf("C3", "a1", "b2"))
    check("short ignored", S.addRecent(listOf("ab"), "x") == listOf("ab"))
    check("blank ignored", S.addRecent(listOf("ab"), "   ") == listOf("ab"))
    val nine = (1..9).map { "item$it" }
    val capped = S.addRecent(nine.take(8), "newest")
    check("cap 8", capped.size == 8 && capped[0] == "newest" && !capped.contains("item8"))
    check("cap keeps 7", capped.subList(1, 8) == nine.take(7))
    check("remove", S.removeRecent(listOf("Swiggy", "Zomato"), "SWIGGY") == listOf("Zomato"))
    check("roundtrip", S.decodeRecent(S.encodeRecent(listOf("a b", "Zomato"))) == listOf("a b", "Zomato"))
    check("decode null", S.decodeRecent(null).isEmpty())
    check("decode empty", S.decodeRecent("").isEmpty())
    check("decode dedupes", S.decodeRecent("Swiggy\nswiggy\nZomato") == listOf("Swiggy", "Zomato"))
    check("decode caps", S.decodeRecent((1..20).joinToString("\n") { "q$it" }).size == 8)
    // top categories
    val spends = listOf<Pair<String?, Double>>("FOOD" to 100.0, "BILLS" to 500.0, "FOOD" to 450.0, null to 9999.0, "TRAVEL" to 50.0, "SHOPPING" to 60.0)
    check("top 3", S.topCategoryNames(spends, 3) == listOf("FOOD", "BILLS", "SHOPPING"))
    check("top skips null", !S.topCategoryNames(spends, 10).contains("null"))
    check("top none", S.topCategoryNames(emptyList(), 3).isEmpty())
    checks.assertAll()
    }
}
