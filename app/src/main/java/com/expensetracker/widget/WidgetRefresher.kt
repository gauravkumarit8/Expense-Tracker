package com.autoexpensetracker.widget

import android.content.Context
import androidx.glance.appwidget.updateAll

/**
 * Requests an immediate re-render of every placed instance of
 * [ExpenseWidget]. Called from ParseAndStoreWorker right after a
 * successful insert, so the widget reflects a newly captured transaction
 * within moments rather than waiting for the periodic 30-minute fallback
 * update in expense_widget_info.xml.
 *
 * Safe to call even if no widget is currently placed on any home screen —
 * updateAll() is a no-op in that case, not an error.
 */
object WidgetRefresher {
    suspend fun refresh(context: Context) {
        ExpenseWidget().updateAll(context)
    }
}
