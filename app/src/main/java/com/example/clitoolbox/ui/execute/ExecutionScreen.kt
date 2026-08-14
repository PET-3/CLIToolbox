package com.example.clitoolbox.ui.execute

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.clitoolbox.core.executor.CommandExecutor
import com.example.clitoolbox.core.executor.ExecutionEvent
import com.example.clitoolbox.core.executor.ExecutionState
import com.example.clitoolbox.tool.ExecutionSession
import com.example.clitoolbox.tool.HistoryEntry
import com.example.clitoolbox.tool.HistoryRepository
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExecutionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val pending = ExecutionSession.pending
    val historyRepo = remember { HistoryRepository(context) }
    val executor = remember { CommandExecutor() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val lines = remember { mutableStateListOf<Pair<Boolean, String>>() } // (isStderr, text)
    var state by remember { mutableStateOf(ExecutionState.IDLE) }
    var exitCode by remember { mutableStateOf<Int?>(null) }
    var started by remember { mutableStateOf(false) }

    LaunchedEffect(pending) {
        if (pending == null || started) return@LaunchedEffect
        started = true
        executor.execute(pending.argv, File(pending.workingDir)).collect { event ->
            when (event) {
                is ExecutionEvent.StateChanged -> state = event.state
                is ExecutionEvent.Stdout -> lines.add(false to event.line)
                is ExecutionEvent.Stderr -> lines.add(true to event.line)
                is ExecutionEvent.Finished -> {
                    state = event.state
                    exitCode = event.exitCode
                    historyRepo.add(
                        HistoryEntry(
                            id = UUID.randomUUID().toString(),
                            toolId = pending.toolId,
                            toolName = pending.toolName,
                            commandString = pending.commandString,
                            timestamp = System.currentTimeMillis(),
                            result = event.state.name
                        )
                    )
                }
            }
            if (lines.isNotEmpty()) scope.launch { listState.animateScrollToItem(lines.size - 1) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pending?.toolName ?: "Execution") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null) } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            AssistChip(onClick = {}, label = { Text(statusLabel(state, exitCode)) }, modifier = Modifier.padding(8.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)
            ) {
                items(lines) { (isErr, text) ->
                    Text(
                        text,
                        color = if (isErr) Color(0xFFB00020) else Color.Unspecified,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Row(Modifier.padding(16.dp).fillMaxWidth()) {
                Button(
                    onClick = { executor.cancel() },
                    enabled = state == ExecutionState.RUNNING,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Cancel") }
            }
        }
    }
}

private fun statusLabel(state: ExecutionState, exitCode: Int?) = when (state) {
    ExecutionState.IDLE -> "Idle"
    ExecutionState.RUNNING -> "Running…"
    ExecutionState.SUCCESS -> "Success (exit $exitCode)"
    ExecutionState.FAILED -> "Failed (exit $exitCode)"
    ExecutionState.CANCELLED -> "Cancelled"
}
