package com.autoexpensetracker.util

import com.autoexpensetracker.data.Category

/**
 * Which categories a monthly limit can be set on.
 *
 * Transfers are excluded, as in the budgeting apps people already know:
 * a transfer (NEFT/IMPS/RTGS, rent paid to a landlord's account, moving
 * savings) moves money rather than spending it, and one large transfer would
 * otherwise show a ₹30,000 limit as ten times over. Transfers still appear
 * in the transaction list, in Charts and in report totals; they just never
 * count against, or warn about, a budget.
 */
object BudgetPolicy {
    fun isBudgetable(category: Category): Boolean = category != Category.TRANSFER
}
