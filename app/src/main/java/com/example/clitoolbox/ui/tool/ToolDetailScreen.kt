package com.example.clitoolbox.ui.tool

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.clitoolbox.command.CommandBuilder
import com.example.clitoolbox.command.CommandParser
import com.example.clitoolbox.command.SchemaState
import com.example.clitoolbox.command.SchemaStateDefaults
import com.example.clitoolbox.core.model.Tool
import com.example.clitoolbox.tool.ExecutionSession
import com.example.clitoolbox.tool.ToolRepository
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolDetailScreen(
    toolId: String,
    onBack: () -> Unit,
    onEditSchema: () -> Unit,
    onExecute: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { ToolRepository(context) }
    var tool by remember { mutableStateOf(repository.getTool(toolId)) }
    val schema = tool?.schema

    var tab by remember { mutableStateOf(0) }
    var state by remember(schema) { mutableStateOf<SchemaState>(schema?.let { SchemaStateDefaults.buildDefaults(it) } ?: emptyMap()) }
    var commandText by remember(schema) { mutableStateOf(schema?.let { CommandBuilder.buildCommandString(it, state) } ?: "") }
    var unknownTokens by remember { mutableStateOf<List<String>>(emptyList()) }
    val clipboard = LocalClipboardManager.current

    // Keep the command string in sync when GUI state changes.
    LaunchedEffect(state, tab) {
        if (schema != null && tab == 0) {
            commandText = CommandBuilder.buildCommandString(schema, state)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tool?.name ?: "Tool") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null) } },
                actions = {
                    TextButton(onClick = onEditSchema) { Text("Schema") }
                }
            )
        }
    ) { padding ->
        if (schema == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(tool?.analysisSummary?.message ?: "No schema available. Try re-analyzing this tool.")
            }
            return@Scaffold
        }

        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Graphical") })
                Tab(selected = tab == 1, onClick = {
                    tab = 1
                    commandText = CommandBuilder.buildCommandString(schema, state)
                }, text = { Text("Command") })
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                if (tab == 0) {
                    com.example.clitoolbox.ui.schema.SchemaDrivenForm(
                        schema = schema,
                        state = state,
                        onStateChange = { id, value ->
                            state = if (value == null) state - id else state + (id to value)
                        }
                    )
                } else {
                    OutlinedTextField(
                        value = commandText,
                        onValueChange = { commandText = it },
                        label = { Text("Command") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions.Default
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(onClick = {
                            val parsed = CommandParser.parse(schema, commandText)
                            state = parsed.state
                            unknownTokens = parsed.unknownTokens
                        }) { Text("Parse command") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(commandText)) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy")
                        }
                    }
                    if (unknownTokens.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Unrecognized tokens (preserved, not applied to GUI):", style = MaterialTheme.typography.labelMedium)
                        Text(unknownTokens.joinToString(" "), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Divider()
            Row(Modifier.padding(16.dp).fillMaxWidth()) {
                Text(
                    text = if (tab == 0) CommandBuilder.buildCommandString(schema, state) else commandText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
            }
            Button(
                onClick = {
                    val argv = CommandBuilder.buildArgv(schema, state)
                    val workDir = File(tool!!.binaryPath).parentFile ?: context.filesDir
                    ExecutionSession.pending = ExecutionSession.Pending(
                        toolId = toolId,
                        toolName = tool!!.name,
                        argv = argv,
                        commandString = CommandBuilder.buildCommandString(schema, state),
                        workingDir = workDir.absolutePath
                    )
                    onExecute()
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
            ) { Text("Execute") }
        }
    }
}
