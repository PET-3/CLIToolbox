package com.example.clitoolbox.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.clitoolbox.ui.theme.AppThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onSettingsChanged: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    var theme by remember { mutableStateOf(store.themeMode) }
    var language by remember { mutableStateOf(store.languageTag) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null) } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxWidth()) {
            Text("Theme", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            AppThemeMode.entries.forEach { mode ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    RadioButton(selected = theme == mode, onClick = {
                        theme = mode
                        store.themeMode = mode
                        onSettingsChanged()
                    })
                    Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("Language", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            val options = listOf("system" to "System Default", "en" to "English", "zh-rCN" to "简体中文")
            options.forEach { (tag, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    RadioButton(selected = language == tag, onClick = {
                        language = tag
                        store.languageTag = tag
                        applyAppLocale(tag)
                        onSettingsChanged()
                    })
                    Text(label)
                }
            }
        }
    }
}

private fun applyAppLocale(tag: String) {
    val locales = if (tag == "system") {
        androidx.core.os.LocaleListCompat.getEmptyLocaleList()
    } else {
        androidx.core.os.LocaleListCompat.forLanguageTags(tag)
    }
    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
}
