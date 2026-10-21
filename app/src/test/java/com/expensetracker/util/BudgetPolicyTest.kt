package com.autoexpensetracker.util

import com.autoexpensetracker.Checks
import com.autoexpensetracker.data.Category
import org.junit.Test

class BudgetPolicyTest {
    @Test
    fun transfersAreNotBudgetableEverythingElseIs() {
        val c = Checks()
        c.check("transfer excluded", !BudgetPolicy.isBudgetable(Category.TRANSFER))
        for (cat in Category.values()) {
            if (cat != Category.TRANSFER) c.check("${cat.name} budgetable", BudgetPolicy.isBudgetable(cat))
        }
        c.check("exactly one excluded", Category.values().count { !BudgetPolicy.isBudgetable(it) } == 1)
        c.assertAll()
    }
}
