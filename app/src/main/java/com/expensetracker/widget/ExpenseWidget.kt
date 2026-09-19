package com.autoexpensetracker.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.autoexpensetracker.data.AppDatabase
import com.autoexpensetracker.data.Direction
import com.autoexpensetracker.util.MonthRange
import com.autoexpensetracker.util.formatInr

/**
 * Home-screen widget: this month's net spend, sent, and received, in the
 * same three-number shape as the in-app Dashboard hero card — deliberately
 * not a quick-add-transaction widget, unlike most manual-entry expense
 * trackers' widgets. Since transactions here are auto-captured rather than
 * manually logged, a glanceable summary is the actually useful surface for
 * a widget, not shortcut buttons to a form nobody needs to open.
 *
 * IMPORTANT — this is genuinely the least-verified piece of UI in this
 * codebase: Glance renders composables to RemoteViews under the hood,
 * which is a materially different and more constrained rendering pipeline
 * than regular Compose (limited layout primitives, no arbitrary Modifier
 * chains, system-imposed size/update-frequency constraints), and there is
 * no way to preview or compile-check this without a real device/emulator.
 * Treat this as needing an actual home-screen placement test before
 * considering it done, more than anything else shipped this session.
 */
class ExpenseWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = AppDatabase.getInstance(context.applicationContext).transactionDao()
        val monthRange = MonthRange.current()
        val allTransactions = dao.getAllOnce()
        val monthTransactions = allTransactions.filter { monthRange.contains(it.timestampMillis) }
        val spent = monthTransactions.filter { it.direction == Direction.SENT }.sumOf { it.amount }
        val received = monthTransactions.filter { it.direction == Direction.RECEIVED }.sumOf { it.amount }
        val net = received - spent

        provideContent {
            WidgetContent(monthLabel = monthRange.label(), net = net, spent = spent, received = received)
        }
    }
}

@androidx.compose.runtime.Composable
private fun WidgetContent(monthLabel: String, net: Double, spent: Double, received: Double) {
    // Brand green (#2E7D32) / white, as fixed values rather than
    // MaterialTheme.colorScheme references — Glance composables don't sit
    // inside the app's own ExpenseTrackerTheme, so there's no
    // MaterialTheme to read from here; matching the brand color has to be
    // done directly.
    val brandGreen = ColorProvider(androidx.compose.ui.graphics.Color(0xFF2E7D32))
    val white = ColorProvider(androidx.compose.ui.graphics.Color.White)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(brandGreen)
            .appWidgetBackground()
            .padding(16.dp)
    ) {
        Text(
            monthLabel,
            style = TextStyle(color = white, fontSize = 12.sp)
        )
        Spacer(modifier = GlanceModifier.height(4.dp))
        Text(
            "${if (net >= 0) "+" else "−"}${formatInr(kotlin.math.abs(net))}",
            style = TextStyle(color = white, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = GlanceModifier.height(8.dp))
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text("Sent", style = TextStyle(color = white, fontSize = 10.sp))
                Text(formatInr(spent), style = TextStyle(color = white, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            }
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text("Received", style = TextStyle(color = white, fontSize = 10.sp))
                Text(formatInr(received), style = TextStyle(color = white, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            }
        }
    }
}