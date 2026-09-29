package com.autoexpensetracker.util

import android.content.Context
import com.autoexpensetracker.data.BudgetDao
import com.autoexpensetracker.data.Category
import com.autoexpensetracker.data.Direction
import com.autoexpensetracker.data.Transaction
import com.autoexpensetracker.data.TransactionDao
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Budgets used to only ever show "over budget" AFTER the limit was already
 * crossed (a red bar on BudgetsScreen you'd only see if you opened the
 * app). This adds a proactive check: right after a new transaction is
 * inserted, project this category's likely month-end spend from the
 * current daily run rate, and notify the FIRST time either (a) that
 * projection crosses the limit, or (b) actual spend crosses the limit —
 * whichever happens first — so the warning arrives while there's still
 * time to act, not just as a retrospective red bar.
 *
 * At most one notification per category per month (see
 * [BudgetWarningStore]) — this is meant to be a single heads-up, not a
 * repeat alert on every transaction after the threshold.
 */
object BudgetWarningDetector {

    // Same >=3-elapsed-days guard as the BudgetsScreen UI indicator: one or
    // two early big-ticket purchases (rent, an annual payment) on the 1st
    // or 2nd of the month would otherwise produce a wildly overstated
    // projection.
    private const val MIN_DAYS_ELAPSED_FOR_PROJECTION = 3

    private val yearMonthFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())

    suspend fun checkAndNotify(
        context: Context,
        transactionDao: TransactionDao,
        budgetDao: BudgetDao,
        newTransaction: Transaction
    ) {
        if (newTransaction.direction != Direction.SENT || newTransaction.needsReview) return
        val category = Category.fromNameOrNull(newTransaction.category) ?: return

        val budget = budgetDao.getAllOnce().firstOrNull { it.category == category.name } ?: return
        if (budget.monthlyLimit <= 0) return

        val cal = Calendar.getInstance().apply { timeInMillis = newTransaction.timestampMillis }
        val yearMonth = yearMonthFormat.format(cal.time)
        if (BudgetWarningStore.alreadyWarned(context, category.name, yearMonth)) return

        val startOfMonth = (cal.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val spentThisMonth = transactionDao.getAllOnce().filter {
            it.direction == Direction.SENT &&
                it.timestampMillis >= startOfMonth &&
                it.timestampMillis <= newTransaction.timestampMillis &&
                Category.fromNameOrNull(it.category) == category
        }.sumOf { it.amount }

        if (spentThisMonth > budget.monthlyLimit) {
            // Actually crossed — notify now regardless of days elapsed.
            BudgetWarningStore.markWarned(context, category.name, yearMonth)
            BudgetWarningNotificationHelper.show(context, category, spentThisMonth, budget.monthlyLimit, projected = false)
            return
        }

        val daysElapsed = cal.get(Calendar.DAY_OF_MONTH)
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        if (daysElapsed < MIN_DAYS_ELAPSED_FOR_PROJECTION || spentThisMonth <= 0) return

        val projected = spentThisMonth / daysElapsed * daysInMonth
        if (projected > budget.monthlyLimit) {
            BudgetWarningStore.markWarned(context, category.name, yearMonth)
            BudgetWarningNotificationHelper.show(context, category, projected, budget.monthlyLimit, projected = true)
        }
    }
}
