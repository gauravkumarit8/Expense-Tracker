package com.autoexpensetracker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autoexpensetracker.ui.theme.MutedText

/**
 * "How is this month going?": the lines come from MonthPace (pure, tested),
 * this only draws them. Not shown at all when there is nothing honest to say
 * (no spending yet and no budgets, or too early in the month to project).
 */
@Composable
internal fun MonthPaceCard(lines: List<String>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("This month's pace", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            for (line in lines) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MutedText)
            }
        }
    }
}
