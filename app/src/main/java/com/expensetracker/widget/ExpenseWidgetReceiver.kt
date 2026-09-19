package com.autoexpensetracker.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * The actual manifest-registered widget provider — Glance requires this
 * thin receiver wrapper around the GlanceAppWidget itself (see
 * AndroidManifest.xml's <receiver> entry and res/xml/expense_widget_info.xml).
 */
class ExpenseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ExpenseWidget()
}
