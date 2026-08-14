package com.example.clitoolbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.clitoolbox.ui.execute.ExecutionScreen
import com.example.clitoolbox.ui.history.HistoryScreen
import com.example.clitoolbox.ui.home.HomeScreen
import com.example.clitoolbox.ui.schema.SchemaEditorScreen
import com.example.clitoolbox.ui.settings.SettingsScreen
import com.example.clitoolbox.ui.settings.SettingsStore
import com.example.clitoolbox.ui.theme.CliToolboxTheme
import com.example.clitoolbox.ui.tool.ToolDetailScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val settingsStore = remember { SettingsStore(context) }
            var themeMode by remember { mutableStateOf(settingsStore.themeMode) }

            CliToolboxTheme(themeMode = themeMode) {
                AppNavHost(onThemeChanged = { themeMode = settingsStore.themeMode })
            }
        }
    }
}

private object Routes {
    const val HOME = "home"
    const val TOOL = "tool/{toolId}"
    const val SCHEMA = "schema/{toolId}"
    const val EXECUTE = "execute"
    const val HISTORY = "history"
    const val SETTINGS = "settings"

    fun tool(id: String) = "tool/$id"
    fun schema(id: String) = "schema/$id"
}

@Composable
private fun AppNavHost(onThemeChanged: () -> Unit) {
    val navController: NavHostController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenTool = { tool -> navController.navigate(Routes.tool(tool.id)) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.TOOL) { backStackEntry ->
            val toolId = backStackEntry.arguments?.getString("toolId") ?: return@composable
            ToolDetailScreen(
                toolId = toolId,
                onBack = { navController.popBackStack() },
                onEditSchema = { navController.navigate(Routes.schema(toolId)) },
                onExecute = { navController.navigate(Routes.EXECUTE) }
            )
        }
        composable(Routes.SCHEMA) { backStackEntry ->
            val toolId = backStackEntry.arguments?.getString("toolId") ?: return@composable
            SchemaEditorScreen(toolId = toolId, onBack = { navController.popBackStack() })
        }
        composable(Routes.EXECUTE) {
            ExecutionScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.HISTORY) {
            HistoryScreen(
                onBack = { navController.popBackStack() },
                onReloadCommand = { entry ->
                    navController.popBackStack()
                    navController.navigate(Routes.tool(entry.toolId))
                }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() }, onSettingsChanged = onThemeChanged)
        }
    }
}
