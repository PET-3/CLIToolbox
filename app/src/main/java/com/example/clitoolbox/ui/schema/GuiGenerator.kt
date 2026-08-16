package com.example.clitoolbox.ui.schema

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.clitoolbox.command.ArgumentValue
import com.example.clitoolbox.command.OutputPathResolver
import com.example.clitoolbox.command.OutputPathResult
import com.example.clitoolbox.command.SchemaState
import com.example.clitoolbox.core.schema.ArgumentType
import com.example.clitoolbox.core.schema.SchemaArgument
import com.example.clitoolbox.core.schema.ToolSchema

/**
 * Pure GUI Generator: Schema -> Compose widgets. Contains no knowledge of any
 * specific tool — only reacts to [SchemaArgument.type]. This is the only
 * place argument widgets are chosen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemaDrivenForm(
    schema: ToolSchema,
    state: SchemaState,
    onStateChange: (String, ArgumentValue?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        schema.groups.sortedBy { it.order }.forEach { group ->
            Text(group.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            group.arguments.sortedBy { it.order }.forEach { arg ->
                ArgumentWidget(
                    arg,
                    state[arg.id],
                    onValueChange = { onStateChange(arg.id, it) },
                    referenceInputPath = referenceInputPathFor(schema, state, arg)
                )
                if (!arg.recognized) {
                    Text("⚠ ${arg.flag ?: arg.id} (unrecognized)", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/**
 * For an output-path argument, finds the value of the first non-output
 * FILE-typed argument in the Schema to use as the {input_name}/{input_stem}/
 * {input_ext} template source. Best-effort — if no such argument has a value
 * yet, template variables simply resolve to empty strings.
 */
private fun referenceInputPathFor(schema: ToolSchema, state: SchemaState, arg: SchemaArgument): String? {
    if (!arg.isOutputPath) return null
    val inputArg = schema.allArguments().firstOrNull { it.type == ArgumentType.FILE && !it.isOutputPath } ?: return null
    return (state[inputArg.id] as? ArgumentValue.Path)?.value
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArgumentWidget(
    arg: SchemaArgument,
    value: ArgumentValue?,
    onValueChange: (ArgumentValue?) -> Unit,
    referenceInputPath: String? = null
) {
    when (arg.type) {
        ArgumentType.TEXT -> {
            val text = (value as? ArgumentValue.Text)?.value ?: ""
            OutlinedTextField(
                value = text,
                onValueChange = { onValueChange(if (it.isBlank()) null else ArgumentValue.Text(it)) },
                label = { Text(arg.label) },
                supportingText = arg.description?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth()
            )
        }
        ArgumentType.NUMBER -> {
            val current = (value as? ArgumentValue.Number)?.value
            if (arg.min != null && arg.max != null) {
                Column(Modifier.fillMaxWidth()) {
                    Text("${arg.label}: ${current?.let { formatNum(it) } ?: "—"}")
                    Slider(
                        value = (current ?: arg.min).toFloat(),
                        onValueChange = { onValueChange(ArgumentValue.Number(it.toDouble())) },
                        valueRange = arg.min.toFloat()..arg.max.toFloat(),
                        steps = stepsFor(arg)
                    )
                }
            } else {
                OutlinedTextField(
                    value = current?.let { formatNum(it) } ?: "",
                    onValueChange = { text -> onValueChange(text.toDoubleOrNull()?.let { ArgumentValue.Number(it) }) },
                    label = { Text(arg.label) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        ArgumentType.BOOLEAN, ArgumentType.FLAG -> {
            val checked = (value as? ArgumentValue.Bool)?.value ?: false
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(arg.label, modifier = Modifier.weight(1f))
                Switch(checked = checked, onCheckedChange = { onValueChange(ArgumentValue.Bool(it)) })
            }
        }
        ArgumentType.SELECT -> {
            var expanded by remember { mutableStateOf(false) }
            val current = (value as? ArgumentValue.Choice)?.value ?: ""
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = current,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(arg.label) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    arg.values.forEach { v ->
                        DropdownMenuItem(text = { Text(v) }, onClick = { onValueChange(ArgumentValue.Choice(v)); expanded = false })
                    }
                }
            }
        }
        ArgumentType.MULTI_SELECT -> {
            val selected = (value as? ArgumentValue.MultiChoice)?.values ?: emptyList()
            Column {
                Text(arg.label)
                FlowRowSimple {
                    arg.values.forEach { v ->
                        FilterChip(
                            selected = selected.contains(v),
                            onClick = {
                                val next = if (selected.contains(v)) selected - v else selected + v
                                onValueChange(if (next.isEmpty()) null else ArgumentValue.MultiChoice(next))
                            },
                            label = { Text(v) }
                        )
                    }
                }
            }
        }
        ArgumentType.FILE, ArgumentType.FILES, ArgumentType.DIRECTORY -> {
            if (arg.isOutputPath) {
                OutputPathField(arg, value, referenceInputPath, onValueChange)
            } else {
                FilePickerField(arg, value, onValueChange)
            }
        }
    }
}

@Composable
private fun FilePickerField(arg: SchemaArgument, value: ArgumentValue?, onValueChange: (ArgumentValue?) -> Unit) {
    val currentLabel = when (value) {
        is ArgumentValue.Path -> value.value
        is ArgumentValue.Paths -> value.values.joinToString(", ")
        else -> ""
    }
    val pickMultiple = arg.type == ArgumentType.FILES
    val launcher = if (pickMultiple) {
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) onValueChange(ArgumentValue.Paths(uris.map { it.toString() }))
        }
    } else {
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { onValueChange(ArgumentValue.Path(it.toString())) }
        }
    }
    OutlinedTextField(
        value = currentLabel,
        onValueChange = { text -> onValueChange(if (text.isBlank()) null else ArgumentValue.Path(text)) },
        label = { Text(arg.label) },
        trailingIcon = {
            TextButton(onClick = { launcher.launch(if (pickMultiple) arrayOf("*/*") else arrayOf("*/*")) }) { Text("Browse") }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun OutputPathField(
    arg: SchemaArgument,
    value: ArgumentValue?,
    referenceInputPath: String?,
    onValueChange: (ArgumentValue?) -> Unit
) {
    // Raw text is tracked separately from the committed value so the user can
    // keep typing through an intermediate invalid state (e.g. while typing "..")
    // without the field fighting them — but nothing invalid is ever committed
    // to GUI state / passed to CommandBuilder.
    var rawText by remember(value) {
        mutableStateOf((value as? ArgumentValue.Path)?.value ?: "")
    }
    var error by remember { mutableStateOf<String?>(null) }

    OutlinedTextField(
        value = rawText,
        onValueChange = { text ->
            rawText = text
            when (val result = OutputPathResolver.resolve(text, referenceInputPath)) {
                is OutputPathResult.Resolved -> {
                    error = null
                    onValueChange(ArgumentValue.Path(result.fileName))
                }
                is OutputPathResult.Rejected -> {
                    error = result.reason
                    onValueChange(null)
                }
            }
        },
        label = { Text(arg.label) },
        isError = error != null,
        supportingText = {
            Text(error ?: arg.description ?: "File name only — supports {input_name}, {input_stem}, {input_ext}")
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun FlowRowSimple(content: @Composable () -> Unit) {
    // Minimal wrap layout without extra dependencies.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

private fun formatNum(n: Double) = if (n == n.toLong().toDouble()) n.toLong().toString() else n.toString()
private fun stepsFor(arg: SchemaArgument): Int {
    val step = arg.step ?: return 0
    val min = arg.min ?: return 0
    val max = arg.max ?: return 0
    if (step <= 0) return 0
    return (((max - min) / step) - 1).toInt().coerceAtLeast(0)
}
