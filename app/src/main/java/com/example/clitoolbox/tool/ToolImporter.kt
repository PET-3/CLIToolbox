package com.example.clitoolbox.tool

import android.content.Context
import android.net.Uri
import android.os.Build
import com.example.clitoolbox.core.model.AndroidCompatibility
import com.example.clitoolbox.core.model.Tool
import com.example.clitoolbox.core.model.ToolArchitecture
import com.example.clitoolbox.core.model.ToolSource
import java.io.File
import java.util.UUID

sealed class ImportResult {
    data class Success(val tool: Tool) : ImportResult()
    data class Failure(val reason: String) : ImportResult()
    data class UnsupportedArchitecture(val architecture: ToolArchitecture) : ImportResult()
    /** ABI matched, but static inspection strongly indicates a glibc (non-Android) Linux build. */
    data class IncompatibleRuntime(val reason: String) : ImportResult()
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
            if (!ElfInspector.isAbiSupportedOnThisDevice(elfInfo.architecture, supportedAbis)) {
                destFile.delete()
                return ImportResult.UnsupportedArchitecture(elfInfo.architecture)
            }

            // ABI matching alone doesn't mean the binary will actually run — a
            // glibc-linked Linux build can still be arm64-v8a. Check for that
            // before accepting the import.
            val compatibility = ElfInspector.inspectAndroidCompatibility(destFile)
            if (compatibility == AndroidCompatibility.LIKELY_INCOMPATIBLE_GLIBC) {
                destFile.delete()
                return ImportResult.IncompatibleRuntime(
                    "This appears to be a standard Linux (glibc) executable, not an Android build. " +
                        "It will very likely fail to launch on this device even though its CPU architecture matches."
                )
            }

            ImportResult.Success(
                Tool(
                    id = toolId,
                    name = displayNameFor(name),
                    executableName = name,
                    architecture = elfInfo.architecture,
                    androidCompatibility = compatibility,
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
