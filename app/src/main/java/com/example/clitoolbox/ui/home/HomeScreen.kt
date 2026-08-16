package com.example.clitoolbox.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.clitoolbox.core.model.Tool
import com.example.clitoolbox.tool.ImportAndAnalyzeResult
import com.example.clitoolbox.tool.ToolManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenTool: (Tool) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val toolManager = remember { ToolManager(context) }
    var tools by remember { mutableStateOf(toolManager.repo().listTools()) }
    var analyzing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        tools = toolManager.repo().listTools()
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        analyzing = true
        scope.launch {
            val name = queryDisplayName(context, uri)
            val result = toolManager.importAndAnalyze(uri, name)
            analyzing = false
            when (result) {
                is ImportAndAnalyzeResult.Success -> refresh()
                is ImportAndAnalyzeResult.AnalysisFailed -> {
                    refresh()
                    errorMessage = result.reason
                }
                is ImportAndAnalyzeResult.ImportFailed -> errorMessage = result.reason
                is ImportAndAnalyzeResult.UnsupportedArchitecture ->
                    errorMessage = "Unsupported architecture: ${result.architecture}"
                is ImportAndAnalyzeResult.IncompatibleRuntime -> errorMessage = result.reason
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(context, "home_title")) },
                actions = {
                    IconButton(onClick = onOpenHistory) { Icon(Icons.Default.History, contentDescription = null) }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = null) }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(context, "action_import_tool")) }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (tools.isEmpty() && !analyzing) {
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("No tools yet.", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Tap \"Import Tool\" to add an executable like ffmpeg, 7zz, or yt-dlp.")
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(tools, key = { it.id }) { tool ->
                        ToolCard(
                            tool = tool,
                            onClick = { onOpenTool(tool) },
                            onReanalyze = {
                                analyzing = true
                                scope.launch {
                                    val result = toolManager.reanalyze(tool)
                                    analyzing = false
                                    refresh()
                                    if (result is ImportAndAnalyzeResult.AnalysisFailed) errorMessage = result.reason
                                }
                            },
                            onDelete = {
                                toolManager.delete(tool.id)
                                refresh()
                            }
                        )
                    }
                }
            }

            if (analyzing) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 4.dp
                ) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(context, "analyzing_title"))
                    }
                }
            }
        }
    }

    errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("OK") } },
            title = { Text("Analysis") },
            text = { Text(msg) }
        )
    }
}

@Composable
private fun ToolCard(
    tool: Tool,
    onClick: () -> Unit,
    onReanalyze: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tool.name, style = MaterialTheme.typography.titleMedium)
                val version = tool.version?.let { " · $it" } ?: ""
                Text("${tool.architecture}$version", style = MaterialTheme.typography.bodySmall)
                if (tool.androidCompatibility == com.example.clitoolbox.core.model.AndroidCompatibility.UNKNOWN) {
                    Text("⚠ Android compatibility could not be confirmed", style = MaterialTheme.typography.labelSmall)
                }
                tool.analysisSummary?.let { s ->
                    val status = if (s.succeeded) {
                        "${s.recognizedArguments} recognized, ${s.unknownArguments} unknown"
                    } else {
                        s.message ?: "Analysis failed"
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.Settings, contentDescription = null) }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(context, "action_reanalyze")) }, onClick = { showMenu = false; onReanalyze() })
                    DropdownMenuItem(text = { Text(stringResource(context, "action_delete")) }, onClick = { showMenu = false; confirmDelete = true })
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(context, "action_delete")) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(context, "action_cancel")) } },
            title = { Text("Delete ${tool.name}?") },
            text = { Text("This removes the tool and its imported binary.") }
        )
    }
}

private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun stringResource(context: android.content.Context, name: String): String {
    val id = context.resources.getIdentifier(name, "string", context.packageName)
    return if (id != 0) androidx.compose.ui.res.stringResource(id) else name
}
