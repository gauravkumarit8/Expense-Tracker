package com.autoexpensetracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autoexpensetracker.data.Category
import com.autoexpensetracker.ui.theme.MutedText
import com.autoexpensetracker.util.CategoryRuleStore

/**
 * Every "always use this category for this merchant" rule, with a way to
 * remove each one. Removing a rule stops it applying to FUTURE transactions;
 * transactions already re-categorised keep the category they were given.
 */
@Composable
internal fun CategoryRulesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var rules by remember { mutableStateOf(CategoryRuleStore.getAll(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Category rules") },
        text = {
            Column {
                if (rules.isEmpty()) {
                    Text(
                        "No rules yet. When you change a transaction's category you can choose to use it for every " +
                            "transaction from that merchant. Those choices will be listed here.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        "New transactions from these merchants get the category shown. Removing a rule does not " +
                            "change transactions that were already updated.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                        items(rules, key = { it.key }) { rule ->
                            val category = Category.fromNameOrNull(rule.category) ?: Category.OTHER
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(rule.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${category.emoji} ${category.label}", style = MaterialTheme.typography.bodySmall, color = MutedText)
                                }
                                IconButton(onClick = { rules = CategoryRuleStore.remove(context, rule.key) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Remove the rule for ${rule.displayName}")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
