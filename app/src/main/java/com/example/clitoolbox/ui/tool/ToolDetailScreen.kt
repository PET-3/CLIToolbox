package com.example.clitoolbox.ui.tool

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import com.example.clitoolbox.command.ParsedCommandState
import com.example.clitoolbox.command.SchemaState
import com.example.clitoolbox.command.SchemaStateDefaults
import com.example.clitoolbox.command.UnknownArgument
import com.example.clitoolbox.tool.ExecutionSession
import com.example.clitoolbox.tool.PendingCommandLoad
import com.example.clitoolbox.tool.ToolRepository
import java.io.File
import java.util.UUID

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
    // Re-read the tool fresh every time this screen is entered, so edits made
    // in the Schema Editor are picked up instead of a stale cached copy.
    var tool by remember(toolId) { mutableStateOf(repository.getTool(toolId)) }
    val schema = tool?.schema

    var tab by remember { mutableStateOf(0) }
    var state by remember(schema) { mutableStateOf<SchemaState>(schema?.let { SchemaStateDefaults.buildDefaults(it) } ?: emptyMap()) }
    var unknownArguments by remember(schema) { mutableStateOf<List<UnknownArgument>>(emptyList()) }
    // Original flagged-argument order from the last parse, if any — see
    // CommandBuilder's flagOrder parameter. Empty for a fresh GUI-only session.
    var flagOrder by remember(schema) { mutableStateOf<List<String>>(emptyList()) }
    var commandText by remember(schema) {
        mutableStateOf(schema?.let { CommandBuilder.buildCommandString(it, state, unknownArguments, flagOrder) } ?: "")
    }
    var unknownDialogMode by remember { mutableStateOf<DialogMode?>(null) }
    var editingUnknownArgument by remember { mutableStateOf<UnknownArgument?>(null) }
    val clipboard = LocalClipboardManager.current

    // Consume a pending History "reload command" request, if this screen was
    // opened for that purpose: re-parse the stored command through the
    // current Schema so both recognized values AND unknown arguments come
    // back, then jump to the Command tab so the user sees exactly what loaded.
    LaunchedEffect(toolId, schema) {
        val pending = PendingCommandLoad.pending
        if (schema != null && pending != null && pending.toolId == toolId) {
            val parsed = CommandParser.parse(schema, pending.commandString)
            state = parsed.values
            unknownArguments = parsed.unknownArguments
            flagOrder = parsed.flagOrder
            commandText = pending.commandString
            tab = 1
            PendingCommandLoad.pending = null
        }
    }

    // Keep the command string in sync when GUI state changes.
    LaunchedEffect(state, unknownArguments, flagOrder, tab) {
        if (schema != null && tab == 0) {
            commandText = CommandBuilder.buildCommandString(schema, state, unknownArguments, flagOrder)
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
                    commandText = CommandBuilder.buildCommandString(schema, state, unknownArguments, flagOrder)
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
                            state = parsed.values
                            unknownArguments = parsed.unknownArguments
                            flagOrder = parsed.flagOrder
                        }) { Text("Parse command") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(commandText)) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy")
                        }
                    }
                }

                // Unknown arguments are always shown (regardless of tab) and are
                // always folded back into the command by CommandBuilder — this is
                // what makes the Command -> Parser -> GUI -> Command loop lossless.
                Spacer(Modifier.height(16.dp))
                UnknownArgumentsSection(
                    unknownArguments = unknownArguments,
                    onRemove = { id -> unknownArguments = unknownArguments.filterNot { it.id == id } },
                    onAddClick = { editingUnknownArgument = UnknownArgument("", null, null); unknownDialogMode = DialogMode.ADD },
                    onEditClick = { unknown -> editingUnknownArgument = unknown; unknownDialogMode = DialogMode.EDIT }
                )
            }

            Divider()
            if (tab == 0) {
                // On the Command tab the editable text field above already shows this
                // live — showing a second, possibly-stale copy here (it only reflects
                // the last Parse, not un-parsed edits) would be confusing rather than
                // helpful, so this preview is GUI-tab only.
                Row(Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(
                        text = CommandBuilder.buildCommandString(schema, state, unknownArguments, flagOrder),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Button(
                onClick = {
                    // If the user is on the Command tab and edited the text directly
                    // without pressing "Parse command" first, re-parse it now so
                    // Execute reflects what's actually on screen rather than silently
                    // using stale GUI-tab state.
                    val effective = if (tab == 1) CommandParser.parse(schema, commandText) else
                        ParsedCommandState(state, unknownArguments, flagOrder)
                    val argv = CommandBuilder.buildArgv(schema, effective.values, effective.unknownArguments, effective.flagOrder)
                    val workDir = File(tool!!.binaryPath).parentFile ?: context.filesDir
                    ExecutionSession.pending = ExecutionSession.Pending(
                        toolId = toolId,
                        toolName = tool!!.name,
                        argv = argv,
                        commandString = CommandBuilder.buildCommandString(schema, effective.values, effective.unknownArguments, effective.flagOrder),
                        workingDir = workDir.absolutePath,
                        architecture = tool!!.architecture
                    )
                    onExecute()
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
            ) { Text("Execute") }
        }
    }

    if (unknownDialogMode != null) {
        val isAdd = unknownDialogMode == DialogMode.ADD
        UnknownArgumentDialog(
            existing = if (isAdd) null else editingUnknownArgument,
            onDismiss = { unknownDialogMode = null },
            onSave = { flag, value ->
                val newEntry = UnknownArgument(
                    id = if (isAdd) UUID.randomUUID().toString().take(8) else editingUnknownArgument!!.id,
                    flag = flag.ifBlank { null },
                    value = value.ifBlank { null }
                )
                unknownArguments = if (isAdd) {
                    unknownArguments + newEntry
                } else {
                    unknownArguments.map { if (it.id == newEntry.id) newEntry else it }
                }
                unknownDialogMode = null
            }
        )
    }
}

private enum class DialogMode { ADD, EDIT }

@Composable
private fun UnknownArgumentsSection(
    unknownArguments: List<UnknownArgument>,
    onRemove: (String) -> Unit,
    onAddClick: () -> Unit,
    onEditClick: (UnknownArgument) -> Unit
) {
    if (unknownArguments.isEmpty()) {
        // No placeholder block, no "None — ..." text — the section itself
        // only appears when there's something unknown to show. A minimal,
        // low-emphasis affordance to add one manually is kept available.
        TextButton(onClick = onAddClick) { Text("+ Add unknown argument") }
        return
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Unknown Arguments", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onAddClick) { Text("+ Add") }
        }
        Text(
            "Not recognized by the Schema, but preserved and still included when you execute. Tap to edit:",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(unknownArguments, key = { it.id }) { unknown ->
                InputChip(
                    selected = false,
                    onClick = { onEditClick(unknown) },
                    label = { Text(unknown.displayText().ifBlank { "(empty)" }) },
                    trailingIcon = {
                        IconButton(onClick = { onRemove(unknown.id) }, modifier = Modifier.size(18.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun UnknownArgumentDialog(
    existing: UnknownArgument?,
    onDismiss: () -> Unit,
    onSave: (flag: String, value: String) -> Unit
) {
    var flag by remember { mutableStateOf(existing?.flag ?: "") }
    var value by remember { mutableStateOf(existing?.value ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add Unknown Argument" else "Edit Unknown Argument") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = flag, onValueChange = { flag = it }, label = { Text("Flag (e.g. --custom-option)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Value (optional)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(flag, value) }, enabled = flag.isNotBlank() || value.isNotBlank()) { Text(if (existing == null) "Add" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
