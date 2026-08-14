package com.example.clitoolbox.tool

import android.content.Context
import android.net.Uri
import android.os.Build
import com.example.clitoolbox.core.model.Tool
import com.example.clitoolbox.core.model.ToolArchitecture
import com.example.clitoolbox.core.model.ToolSource
import java.io.File
import java.util.UUID

sealed class ImportResult {
    data class Success(val tool: Tool) : ImportResult()
    data class Failure(val reason: String) : ImportResult()
    data class UnsupportedArchitecture(val architecture: ToolArchitecture) : ImportResult()
}

/**
 * Imports a user-picked executable (via Storage Access Framework) into
 * app-private storage at filesDir/tools/<id>/bin/<name>, then validates it
 * (exists, readable, non-empty, ELF, ABI) before handing back a Tool. Never
 * executes anything directly from the original content:// / external path.
 */
class ToolImporter(private val context: Context) {

    fun importFromUri(uri: Uri, displayName: String?): ImportResult {
        val toolId = UUID.randomUUID().toString()
        val name = sanitizeExecutableName(displayName ?: "tool")

        val toolDir = File(context.filesDir, "tools/$toolId/bin")
        if (!toolDir.exists() && !toolDir.mkdirs()) {
            return ImportResult.Failure("Could not create storage directory for tool.")
        }
        val destFile = File(toolDir, name)

        return try {
            val resolver = context.contentResolver
            resolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return ImportResult.Failure("Could not open the selected file.")

            if (!destFile.exists() || destFile.length() == 0L) {
                return ImportResult.Failure("Copied file is empty or missing.")
            }
            if (!destFile.canRead()) {
                return ImportResult.Failure("Imported file is not readable.")
            }
            destFile.setExecutable(true, true)
            destFile.setReadable(true, true)
            if (!destFile.canExecute()) {
                return ImportResult.Failure("Could not mark the file as executable.")
            }

            val elfInfo = ElfInspector.inspect(destFile)
            if (!elfInfo.isElf) {
                destFile.delete()
                return ImportResult.Failure("Selected file is not a valid executable (ELF) binary.")
            }

            val supportedAbis = Build.SUPPORTED_ABIS.toList()
            if (!ElfInspector.isSupportedOnThisDevice(elfInfo.architecture, supportedAbis)) {
                return ImportResult.UnsupportedArchitecture(elfInfo.architecture)
            }

            ImportResult.Success(
                Tool(
                    id = toolId,
                    name = displayNameFor(name),
                    executableName = name,
                    architecture = elfInfo.architecture,
                    source = ToolSource.IMPORTED,
                    binaryPath = destFile.absolutePath
                )
            )
        } catch (e: SecurityException) {
            ImportResult.Failure("Permission denied while importing: ${e.message}")
        } catch (e: Exception) {
            ImportResult.Failure("Import failed: ${e.message ?: e.toString()}")
        }
    }

    private fun sanitizeExecutableName(raw: String): String {
        val base = raw.substringAfterLast('/').substringBeforeLast('.').ifBlank { "tool" }
        return base.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun displayNameFor(executableName: String): String =
        executableName.replaceFirstChar { it.uppercase() }
}
