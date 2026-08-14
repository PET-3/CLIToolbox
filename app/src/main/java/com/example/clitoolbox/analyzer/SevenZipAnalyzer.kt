package com.example.clitoolbox.analyzer

import com.example.clitoolbox.core.executor.ProcessRunner
import com.example.clitoolbox.core.model.Tool
import com.example.clitoolbox.core.schema.ArgumentType
import com.example.clitoolbox.core.schema.SchemaArgument
import com.example.clitoolbox.core.schema.SchemaGroup
import com.example.clitoolbox.core.schema.ToolSchema
import java.io.File

/**
 * Specialized analyzer for 7-Zip (7z / 7zz). Produces a Schema covering the
 * common command letters (a/x/e/l/t) and switches (-o/-p/-mx/-r/-y/-sdel).
 * The "command" (a/x/e/l/t) is modeled as a required SELECT positional so
 * the GUI shows one dropdown ("Action") instead of five booleans.
 */
class SevenZipAnalyzer : GenericAnalyzer() {

    override fun supports(tool: Tool): Boolean {
        val name = tool.executableName.lowercase()
        return name == "7z" || name == "7zz" || name.endsWith("/7z") || name.endsWith("/7zz")
    }

    override fun analyze(tool: Tool): ToolAnalysisResult {
        val binary = File(tool.binaryPath)
        val workDir = binary.parentFile ?: File("/")

        val probe = ProcessRunner.probe(binary.absolutePath, emptyList(), workDir, 5)
        if (probe.timedOut) {
            return ToolAnalysisResult.AnalysisFailed("7-Zip did not respond within timeout.")
        }
        val version = probe.stdout.lineSequence().firstOrNull { it.contains("7-Zip") }?.trim()

        val actionGroup = SchemaGroup(
            id = "action", name = "Action", order = 0,
            arguments = listOf(
                SchemaArgument(
                    id = "command", flag = null, label = "Action", type = ArgumentType.SELECT,
                    required = true, order = 0,
                    values = listOf("a", "x", "e", "l", "t"),
                    description = "a=add, x=extract with paths, e=extract flat, l=list, t=test"
                ),
                SchemaArgument(id = "archive", flag = null, label = "Archive File", type = ArgumentType.FILE, required = true, order = 1),
                SchemaArgument(id = "input_files", flag = null, label = "Files to Add", type = ArgumentType.FILES, order = 2)
            )
        )

        val optionsGroup = SchemaGroup(
            id = "options", name = "Options", order = 1,
            arguments = listOf(
                SchemaArgument(id = "output_dir", flag = "-o", label = "Output Directory", type = ArgumentType.DIRECTORY, order = 0, joinedWithValue = true),
                SchemaArgument(id = "password", flag = "-p", label = "Password", type = ArgumentType.TEXT, order = 1, joinedWithValue = true),
                SchemaArgument(id = "compression_level", flag = "-mx", label = "Compression Level (0-9)", type = ArgumentType.NUMBER, order = 2, min = 0.0, max = 9.0, step = 1.0, joinedWithValue = true),
                SchemaArgument(id = "recurse", flag = "-r", label = "Recurse Subdirectories", type = ArgumentType.FLAG, order = 3),
                SchemaArgument(id = "assume_yes", flag = "-y", label = "Assume Yes", type = ArgumentType.FLAG, order = 4, defaultValue = "true"),
                SchemaArgument(id = "archive_type", flag = "-t", label = "Archive Type", type = ArgumentType.SELECT, order = 5,
                    values = listOf("7z", "zip", "tar", "gzip", "bzip2"), joinedWithValue = true),
                SchemaArgument(id = "delete_after", flag = "-sdel", label = "Delete Files After Adding", type = ArgumentType.FLAG, order = 6)
            )
        )

        val schema = ToolSchema(
            toolName = tool.name.ifBlank { "7-Zip" },
            executable = tool.executableName,
            groups = listOf(actionGroup, optionsGroup),
            positionalOrder = listOf("command", "archive", "input_files")
        )

        return ToolAnalysisResult.Success(
            schema = schema,
            recognizedCount = schema.allArguments().size,
            unknownCount = 0,
            detectedVersion = version
        )
    }
}
