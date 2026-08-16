package com.expensetracker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.expensetracker.data.AppDatabase

/**
 * Skeleton UI. Onboarding flow (requesting notification-listener access +
 * biometric lock setup) is an Open Item — see REQUIREMENTS.md.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AppDatabase.getInstance(applicationContext).transactionDao()

        setContent {
            MaterialTheme {
                val transactions by dao.getAll().collectAsStateWithLifecycle(initialValue = emptyList())
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (transactions.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("No transactions yet", style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Enable Notification access in Settings so bank/UPI alerts can be captured.",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 32.dp)
                                )
                            }
                        }
                    } else {
                        LazyColumn(contentPadding = PaddingValues(16.dp)) {
                            items(transactions) { tx ->
                                ListItem(
                                    headlineContent = { Text("${tx.direction} ₹${tx.amount}") },
                                    supportingContent = { Text("${tx.merchantOrContact ?: "Unknown"} • ${tx.bankOrSource}") }
                                )
                                Divider()
                            }
                        }
                    }
                }
            }
        }
    }
}
