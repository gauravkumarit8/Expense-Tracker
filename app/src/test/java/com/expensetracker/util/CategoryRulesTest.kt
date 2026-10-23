package com.autoexpensetracker.util

import com.autoexpensetracker.Checks
import com.autoexpensetracker.data.Direction
import com.autoexpensetracker.data.Transaction
import com.autoexpensetracker.util.CategoryRules.Rule
import org.junit.Test

private fun tx(id: Long, merchant: String?, category: String?) =
    Transaction(rawTextHash = "h$id", id = id, amount = 10.0, direction = Direction.SENT, merchantOrContact = merchant,
        bankOrSource = "B", timestampMillis = 1_700_000_000_000L + id, category = category)

class CategoryRulesTest {
    private val checks = Checks()
    private fun check(n: String, ok: Boolean) = checks.check(n, ok)

    @Test
    fun allChecks() {
        // ---- keys
        check("plain", CategoryRules.keyFor("Swiggy") == "swiggy")
        check("case and punctuation", CategoryRules.keyFor("SWIGGY!") == "swiggy")
        check("upi wrapper and ref stripped", CategoryRules.keyFor("UPI-SWIGGY-500200") == "swiggy")
        check("two words kept", CategoryRules.keyFor("Ravi Kumar") == "ravi kumar")
        check("different people differ", CategoryRules.keyFor("Ravi Kumar") != CategoryRules.keyFor("Ravi Shankar"))
        check("city suffix is a different merchant", CategoryRules.keyFor("SWIGGY BANGALORE") != CategoryRules.keyFor("SWIGGY"))
        check("star separator", CategoryRules.keyFor("SWIGGY*ORDER") == "swiggy order")
        check("hindi kept", CategoryRules.keyFor("कैफ़े चाय") != null)
        check("hindi stable", CategoryRules.keyFor("कैफ़े चाय") == CategoryRules.keyFor("  कैफ़े   चाय "))
        check("null merchant", CategoryRules.keyFor(null) == null)
        check("blank merchant", CategoryRules.keyFor("   ") == null)
        check("only numbers", CategoryRules.keyFor("482913") == null)
        check("only generic words and numbers", CategoryRules.keyFor("UPI 482913 to") == null)
        check("symbols only", CategoryRules.keyFor("@@--") == null)

        // ---- lookup / upsert / remove
        val a = Rule("swiggy", "Swiggy", "FOOD")
        val b = Rule("ravi kumar", "Ravi Kumar", "OTHER")
        check("lookup hit", CategoryRules.lookup(listOf(a, b), "UPI-SWIGGY-12")?.category == "FOOD")
        check("lookup miss", CategoryRules.lookup(listOf(a, b), "Zomato") == null)
        check("lookup null", CategoryRules.lookup(listOf(a, b), null) == null)
        check("upsert adds newest first", CategoryRules.upsert(listOf(a), b).map { it.key } == listOf("ravi kumar", "swiggy"))
        val changed = CategoryRules.upsert(listOf(a, b), Rule("swiggy", "Swiggy", "GROCERIES"))
        check("upsert replaces same key", changed.size == 2 && changed.first().category == "GROCERIES" && changed.count { it.key == "swiggy" } == 1)
        check("remove", CategoryRules.remove(listOf(a, b), "swiggy") == listOf(b))
        check("remove missing is a no-op", CategoryRules.remove(listOf(a), "zzz") == listOf(a))
        var many = emptyList<Rule>()
        for (i in 1..(CategoryRules.MAX_RULES + 25)) many = CategoryRules.upsert(many, Rule("m$i", "M$i", "FOOD"))
        check("cap", many.size == CategoryRules.MAX_RULES)
        check("cap drops the oldest", many.first().key == "m${CategoryRules.MAX_RULES + 25}" && many.none { it.key == "m1" })

        // ---- which other transactions change
        val all = listOf(
            tx(1, "Swiggy", "OTHER"), tx(2, "UPI-SWIGGY-9", "TRANSFER"), tx(3, "Swiggy", "FOOD"),
            tx(4, "Swiggy Instamart", "OTHER"), tx(5, null, "OTHER"), tx(6, "Zomato", "OTHER"), tx(7, "swiggy", null))
        val ids = CategoryRules.idsToChange(all, setOf("swiggy"), "FOOD", excludeId = 1)
        check("changes same-merchant rows that differ", ids.toSet() == setOf(2L, 7L))
        check("skips the edited row", 1L !in ids)
        check("skips rows already in the category", 3L !in ids)
        check("skips a different merchant", 4L !in ids && 6L !in ids)
        check("skips rows with no merchant", 5L !in ids)
        check("null category counts as OTHER", 7L in CategoryRules.idsToChange(all, setOf("swiggy"), "FOOD", 0) && 7L !in CategoryRules.idsToChange(all, setOf("swiggy"), "OTHER", 0))
        check("two keys at once", CategoryRules.idsToChange(all, setOf("swiggy", "zomato"), "FOOD", 1).toSet() == setOf(2L, 6L, 7L))
        check("no keys no change", CategoryRules.idsToChange(all, emptySet(), "FOOD", 1).isEmpty())

        // ---- when to ask
        val rules = listOf(a)
        check("asks for a new category", CategoryRules.shouldAsk(emptyList(), "Swiggy", "OTHER", "FOOD"))
        check("does not ask when unchanged", !CategoryRules.shouldAsk(emptyList(), "Swiggy", "FOOD", "FOOD"))
        check("unchanged OTHER vs null", !CategoryRules.shouldAsk(emptyList(), "Swiggy", null, "OTHER"))
        check("does not ask when the rule already says so", !CategoryRules.shouldAsk(rules, "Swiggy", "OTHER", "FOOD"))
        check("asks when the rule says something else", CategoryRules.shouldAsk(rules, "Swiggy", "FOOD", "GROCERIES"))
        check("does not ask without a usable merchant", !CategoryRules.shouldAsk(emptyList(), null, "OTHER", "FOOD") && !CategoryRules.shouldAsk(emptyList(), "UPI 123", "OTHER", "FOOD"))

        // ---- storage format
        val rs = listOf(Rule("swiggy", "Swiggy", "FOOD"), Rule("कैफ़े चाय", "कैफ़े चाय", "FOOD"), Rule("ravi kumar", "Ravi\tKumar\nJr", "OTHER"))
        val back = CategoryRules.decode(CategoryRules.encode(rs))
        check("round trip count", back.size == 3)
        check("round trip keys", back.map { it.key } == listOf("swiggy", "कैफ़े चाय", "ravi kumar"))
        check("round trip category", back.map { it.category } == listOf("FOOD", "FOOD", "OTHER"))
        check("tabs and newlines in a name cannot break the format", back[2].displayName == "Ravi Kumar Jr")
        check("decode null", CategoryRules.decode(null).isEmpty())
        check("decode empty", CategoryRules.decode("").isEmpty())
        check("decode garbage lines skipped", CategoryRules.decode("nonsense\nFOOD\tswiggy\tSwiggy\n\t\t\nFOOD\t\tx").map { it.key } == listOf("swiggy"))
        check("decode keeps first duplicate", CategoryRules.decode("FOOD\tswiggy\tA\nOTHER\tswiggy\tB").let { it.size == 1 && it[0].category == "FOOD" })
        check("decode missing display falls back to key", CategoryRules.decodeLine("FOOD\tswiggy\t")?.displayName == "swiggy")
        check("decode caps", CategoryRules.decode((1..(CategoryRules.MAX_RULES + 10)).joinToString("\n") { "FOOD\tm$it\tM$it" }).size == CategoryRules.MAX_RULES)
        check("line round trip", CategoryRules.decodeLine(CategoryRules.encodeLine(a)) == a)

        checks.assertAll()
    }
}
