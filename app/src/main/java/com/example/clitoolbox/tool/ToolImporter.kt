package com.example.clitoolbox.tool

import android.content.Context
import android.net.Uri
import android.os.Build
import com.example.clitoolbox.core.executor.ProcessRunner
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
 * (exists, readable, non-empty, ELF, ABI) — including actually attempting to
 * launch it — before handing back a Tool. Never executes anything directly
 * from the original content:// / external path.
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

            applyExecutablePermission(destFile)
            if (!destFile.canExecute()) {
                return ImportResult.Failure(
                    "Could not mark the file as executable (both File.setExecutable() and a chmod fallback failed)."
                )
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

            // The single most important check in this whole function: actually
            // try to launch the binary, rather than trusting chmod/canExecute().
            // Android 10+ blocks execve() on any file in an app's private
            // storage via SELinux (W^X policy) regardless of Unix permission
            // bits — chmod 755 "succeeds" and canExecute() returns true while
            // the OS still refuses to run it, surfacing only as an opaque
            // "error=13, Permission denied" the first time Execute is pressed.
            // Catching that HERE, at import time, with a clear explanation, is
            // exactly what this check is for. See ExecutableLauncher's doc for
            // the full mechanism and why a system-linker wrap works around it.
            val launchVerification = verifyLaunchable(destFile, elfInfo.architecture)
            if (launchVerification != null) {
                destFile.delete()
                return ImportResult.Failure(launchVerification)
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

    /**
     * Sets the executable bit via the normal Java API, then falls back to
     * invoking the system `chmod` binary directly (explicit argv via
     * ProcessBuilder — never a shell string) if that didn't take effect.
     * This only helps with the "permission bit was never set" failure mode;
     * it does nothing for Android 10+'s separate SELinux exec restriction,
     * which [verifyLaunchable] exists to actually catch.
     */
    private fun applyExecutablePermission(file: File) {
        file.setExecutable(true, true)
        file.setReadable(true, true)
        if (!file.canExecute()) {
            try {
                ProcessBuilder(listOf("/system/bin/chmod", "755", file.absolutePath)).start().waitFor()
            } catch (e: Exception) {
                // Fall through — the canExecute() check right after this call
                // (in importFromUri) is what actually surfaces the failure.
            }
        }
    }

    /**
     * Actually attempts to launch [file] (wrapped through the system linker —
     * see [com.example.clitoolbox.core.executor.ExecutableLauncher] — exactly
     * like a real Execute would) and returns a human-readable failure reason,
     * or null if it launched successfully. A statically linked binary is
     * rejected immediately with a specific explanation, since the linker-wrap
     * workaround fundamentally cannot apply to it (see
     * [ElfInspector.hasDynamicInterpreter]'s doc) — there's no point spending
     * a timeout attempting something that cannot work.
     */
    private fun verifyLaunchable(file: File, architecture: ToolArchitecture): String? {
        if (!ElfInspector.hasDynamicInterpreter(file)) {
            return "Tool is not executable: this binary is statically linked. Android blocks running " +
                "any executable placed in an app's private storage (a security policy since Android 10), " +
                "and the usual workaround for that only works for dynamically linked binaries — this one " +
                "has no dynamic linker dependency, so there is currently no way for this app to launch it."
        }

        val result = ProcessRunner.probe(file.absolutePath, emptyList(), file.parentFile ?: file, timeoutSeconds = 3, architecture = architecture)
        if (!result.processStarted) {
            val permissionRelated = result.stderr.contains("EACCES", ignoreCase = true) ||
                result.stderr.contains("Permission denied", ignoreCase = true) ||
                result.stderr.contains("error=13")
            return if (permissionRelated) {
                "Tool is not executable: the operating system refused to launch it (permission denied). " +
                    "This can happen even after chmod on Android 10+ due to a platform security policy. " +
                    "Raw error: ${result.stderr}"
            } else {
                "Tool is not executable: it failed to launch. Raw error: ${result.stderr}"
            }
        }
        return null
    }

    private fun sanitizeExecutableName(raw: String): String {
        val base = raw.substringAfterLast('/').substringBeforeLast('.').ifBlank { "tool" }
        return base.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun displayNameFor(executableName: String): String =
        executableName.replaceFirstChar { it.uppercase() }
}
