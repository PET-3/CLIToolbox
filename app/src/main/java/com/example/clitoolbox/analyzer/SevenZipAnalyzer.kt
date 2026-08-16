package com.example.clitoolbox.analyzer

import com.example.clitoolbox.core.executor.ProcessRunner
import com.example.clitoolbox.core.model.Tool
import com.example.clitoolbox.core.schema.ArgumentType
import com.example.clitoolbox.core.schema.SchemaArgument
import com.example.clitoolbox.core.schema.SchemaGroup
import com.example.clitoolbox.core.schema.ToolSchema
import com.example.clitoolbox.tool.ExecutableNameMatcher
import java.io.File

/**
 * Specialized analyzer for 7-Zip (7z / 7za / 7zr / 7zz / 7zzs). Produces a
 * Schema with four distinct groups — Action, Archive, Input Files, Options —
 * covering the command letters (a/x/e/l/t) and switches
 * (-o/-p/-mx/-r/-y/-t/-sdel). "Action" (a/x/e/l/t) is modeled as a required
 * SELECT positional, not a plain Option, so the GUI shows one dropdown
 * instead of five booleans.
 */
class SevenZipAnalyzer : GenericAnalyzer() {

    /** Known 7-Zip family binary base names (after [ExecutableNameMatcher.normalize]). */
    private val knownBaseNames = setOf("7z", "7za", "7zr", "7zz", "7zzs")

    override fun supports(tool: Tool): Boolean =
        ExecutableNameMatcher.matchesAnyOf(tool.executableName, knownBaseNames)

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
                )
            )
        )

        val archiveGroup = SchemaGroup(
            id = "archive", name = "Archive", order = 1,
            arguments = listOf(
                SchemaArgument(id = "archive", flag = null, label = "Archive", type = ArgumentType.FILE, required = true, order = 0)
            )
        )

        val inputFilesGroup = SchemaGroup(
            id = "input_files_group", name = "Input Files", order = 2,
            arguments = listOf(
                SchemaArgument(id = "input_files", flag = null, label = "Input Files", type = ArgumentType.FILES, order = 0)
            )
        )

        val optionsGroup = SchemaGroup(
            id = "options", name = "Options", order = 3,
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
            groups = listOf(actionGroup, archiveGroup, inputFilesGroup, optionsGroup),
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
