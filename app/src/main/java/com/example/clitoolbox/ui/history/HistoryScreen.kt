package com.example.clitoolbox.ui.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.clitoolbox.tool.HistoryEntry
import com.example.clitoolbox.tool.HistoryRepository
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit, onReloadCommand: (HistoryEntry) -> Unit) {
    val context = LocalContext.current
    val repo = remember { HistoryRepository(context) }
    val entries = remember { repo.list() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null) } }
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No executions yet.")
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
            items(entries, key = { it.id }) { entry ->
                ElevatedCard(
                    onClick = { onReloadCommand(entry) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            Text(entry.toolName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text(resultLabel(entry.result), style = MaterialTheme.typography.labelMedium)
                        }
                        Text(entry.commandString, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Text(
                            DateFormat.getDateTimeInstance().format(Date(entry.timestamp)),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

private fun resultLabel(result: String) = when (result) {
    "SUCCESS" -> "✓ Success"
    "FAILED" -> "✗ Failed"
    "CANCELLED" -> "⊘ Cancelled"
    else -> result
}
