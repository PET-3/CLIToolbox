package com.example.clitoolbox.ui.schema

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.clitoolbox.core.schema.ArgumentType
import com.example.clitoolbox.core.schema.SchemaArgument
import com.example.clitoolbox.core.schema.SchemaGroup
import com.example.clitoolbox.core.schema.SchemaSerializer
import com.example.clitoolbox.core.schema.ToolSchema
import com.example.clitoolbox.tool.ToolRepository
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemaEditorScreen(toolId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { ToolRepository(context) }
    var tool by remember { mutableStateOf(repository.getTool(toolId)) }
    var schema by remember { mutableStateOf(tool?.schema ?: ToolSchema(tool?.name ?: "", tool?.executableName ?: "")) }
    var editingArg by remember { mutableStateOf<SchemaArgument?>(null) }
    var editingGroupId by remember { mutableStateOf<String?>(null) }

    fun persist(updated: ToolSchema) {
        schema = updated
        tool?.let {
            val saved = it.copy(schema = updated, updatedAt = System.currentTimeMillis())
            repository.saveTool(saved)
            tool = saved
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(SchemaSerializer.toJsonString(schema).toByteArray())
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                val text = reader.readText()
                runCatching { SchemaSerializer.fromJsonString(text) }.onSuccess { persist(it) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Schema") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null) } },
                actions = {
                    TextButton(onClick = { exportLauncher.launch("${schema.toolName}.schema.json") }) { Text("Export") }
                    TextButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) { Text("Import") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                val groupId = schema.groups.firstOrNull()?.id ?: "general"
                editingGroupId = groupId
                editingArg = SchemaArgument(id = UUID.randomUUID().toString().take(8), flag = null, label = "New Argument", recognized = false)
            }) { Text("+") }
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
            schema.groups.sortedBy { it.order }.forEach { group ->
                item(key = "header_${group.id}") {
                    Text(group.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
                }
                items(group.arguments.sortedBy { it.order }, key = { it.id }) { arg ->
                    ListItem(
                        headlineContent = { Text(arg.label + if (!arg.recognized) "  ⚠" else "") },
                        supportingContent = { Text("${arg.flag ?: "(positional)"} · ${arg.type.name.lowercase()}") },
                        trailingContent = {
                            IconButton(onClick = {
                                persist(removeArgument(schema, group.id, arg.id))
                            }) { Icon(Icons.Default.Delete, contentDescription = null) }
                        },
                        modifier = Modifier.clickable { editingGroupId = group.id; editingArg = arg }
                    )
                    Divider()
                }
            }
        }
    }

    if (editingArg != null && editingGroupId != null) {
        ArgumentEditDialog(
            argument = editingArg!!,
            onDismiss = { editingArg = null },
            onSave = { updated ->
                persist(upsertArgument(schema, editingGroupId!!, updated))
                editingArg = null
            }
        )
    }
}

private fun removeArgument(schema: ToolSchema, groupId: String, argId: String): ToolSchema =
    schema.copy(groups = schema.groups.map { g ->
        if (g.id == groupId) g.copy(arguments = g.arguments.filterNot { it.id == argId }) else g
    })

private fun upsertArgument(schema: ToolSchema, groupId: String, arg: SchemaArgument): ToolSchema {
    val groupExists = schema.groups.any { it.id == groupId }
    val groups = if (groupExists) {
        schema.groups.map { g ->
            if (g.id != groupId) return@map g
            val exists = g.arguments.any { it.id == arg.id }
            val newArgs = if (exists) g.arguments.map { if (it.id == arg.id) arg else it } else g.arguments + arg
            g.copy(arguments = newArgs)
        }
    } else {
        schema.groups + SchemaGroup(id = groupId, name = "Custom", order = schema.groups.size, arguments = listOf(arg))
    }
    return schema.copy(groups = groups)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArgumentEditDialog(argument: SchemaArgument, onDismiss: () -> Unit, onSave: (SchemaArgument) -> Unit) {
    var label by remember { mutableStateOf(argument.label) }
    var flag by remember { mutableStateOf(argument.flag ?: "") }
    var type by remember { mutableStateOf(argument.type) }
    var required by remember { mutableStateOf(argument.required) }
    var defaultValue by remember { mutableStateOf(argument.defaultValue ?: "") }
    var values by remember { mutableStateOf(argument.values.joinToString(",")) }
    var typeExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Argument") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = flag, onValueChange = { flag = it }, label = { Text("Flag (blank = positional)") }, modifier = Modifier.fillMaxWidth())
                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                    OutlinedTextField(
                        value = type.name.lowercase(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Type") },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        ArgumentType.entries.forEach { t ->
                            DropdownMenuItem(text = { Text(t.name.lowercase()) }, onClick = { type = t; typeExpanded = false })
                        }
                    }
                }
                if (type == ArgumentType.SELECT || type == ArgumentType.MULTI_SELECT) {
                    OutlinedTextField(value = values, onValueChange = { values = it }, label = { Text("Values (comma separated)") }, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(value = defaultValue, onValueChange = { defaultValue = it }, label = { Text("Default value") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Required", modifier = Modifier.weight(1f))
                    Switch(checked = required, onCheckedChange = { required = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    argument.copy(
                        label = label,
                        flag = flag.ifBlank { null },
                        type = type,
                        required = required,
                        defaultValue = defaultValue.ifBlank { null },
                        values = if (values.isBlank()) emptyList() else values.split(",").map { it.trim() },
                        recognized = true
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
