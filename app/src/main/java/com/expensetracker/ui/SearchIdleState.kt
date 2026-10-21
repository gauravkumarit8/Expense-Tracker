@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.autoexpensetracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autoexpensetracker.data.Category
import com.autoexpensetracker.ui.theme.MutedText

/**
 * What the Search screen shows before anything is typed or filtered: recent
 * searches, one-tap quick filters, and a way into the full list. Without
 * this, opening Search used to drop the user straight into every
 * transaction ever captured, which is Transactions/History's job.
 *
 * Callback-only on purpose - the filter state lives in SearchReviewScreen.
 */
@Composable
internal fun SearchIdleState(
    recent: List<String>,
    topCategories: List<Category>,
    totalCount: Int,
    onRecentClick: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
    onQuickSent: () -> Unit,
    onQuickReceived: () -> Unit,
    onQuickToday: () -> Unit,
    onQuickWeek: () -> Unit,
    onQuickMonth: () -> Unit,
    onQuickCategory: (Category) -> Unit,
    onBrowseAll: () -> Unit,
    adSlot: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (recent.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Recent searches", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onClearRecent) { Text("Clear") }
            }
            recent.forEach { query ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    onClick = { onRecentClick(query) }
                ) {
                    Row(
                        modifier = Modifier.padding(start = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(18.dp), tint = MutedText)
                        Text(
                            query,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1
                        )
                        IconButton(onClick = { onRemoveRecent(query) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove $query from recent searches", modifier = Modifier.size(18.dp), tint = MutedText)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text("Quick filters", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            QuickChip("Sent", onQuickSent)
            QuickChip("Received", onQuickReceived)
            QuickChip("Today", onQuickToday)
            QuickChip("This week", onQuickWeek)
            QuickChip("This month", onQuickMonth)
            topCategories.forEach { category ->
                QuickChip("${category.emoji} ${category.label}") { onQuickCategory(category) }
            }
        }
        if (topCategories.isNotEmpty()) {
            Text("Categories shown are your biggest spends this month.", style = MaterialTheme.typography.bodySmall, color = MutedText)
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            "You can also type an amount (340, ₹1,500) to find exact matches, or a category name.",
            style = MaterialTheme.typography.bodySmall,
            color = MutedText
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onBrowseAll, modifier = Modifier.fillMaxWidth()) {
            Text("Browse all transactions ($totalCount)")
        }
        // An ad, when the caller allows one, sits last: well clear of the
        // buttons and chips above (AdMob penalises ads that invite accidental
        // taps) and in its own labelled card.
        if (adSlot != null) {
            Spacer(modifier = Modifier.height(28.dp))
            adSlot()
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun QuickChip(label: String, onClick: () -> Unit) {
    FilterChip(selected = false, onClick = onClick, label = { Text(label) })
}
