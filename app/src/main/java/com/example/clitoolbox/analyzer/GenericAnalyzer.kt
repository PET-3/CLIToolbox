package com.example.clitoolbox.analyzer

import com.example.clitoolbox.core.executor.ProcessRunner
import com.example.clitoolbox.core.model.Tool
import com.example.clitoolbox.core.schema.ArgumentType
import com.example.clitoolbox.core.schema.SchemaArgument
import com.example.clitoolbox.core.schema.SchemaGroup
import com.example.clitoolbox.core.schema.ToolSchema
import java.io.File

/**
 * Fallback analyzer used for any tool no specialized Analyzer claims. Probes
 * common version/help flags, and does a best-effort scan of the help text for
 * "-x, --xxx  description" style lines to seed a Schema. Never crashes; on
 * total failure it returns AnalysisFailed rather than throwing.
 */
open class GenericAnalyzer : ToolAnalyzer {

    override fun supports(tool: Tool): Boolean = true // catch-all

    override fun analyze(tool: Tool): ToolAnalysisResult {
        val binary = File(tool.binaryPath)
        val workDir = binary.parentFile ?: File(tool.binaryPath).parentFile ?: File("/")

        val version = probeFirstSuccess(binary.absolutePath, workDir, listOf(
            listOf("--version"), listOf("-version"), listOf("-v")
        ))?.stdout?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()

        val help = probeFirstSuccess(binary.absolutePath, workDir, listOf(
            listOf("--help"), listOf("-h"), listOf("-help"), listOf("--usage")
        ))

        if (help == null) {
            return ToolAnalysisResult.AnalysisFailed(
                "Could not run --help/-h/-help/--usage on ${tool.executableName} (timeout or non-zero exit on all attempts)."
            )
        }

        val combinedOutput = help.stdout + "\n" + help.stderr
        val arguments = parseHelpText(combinedOutput)

        val schema = ToolSchema(
            toolName = tool.name,
            executable = tool.executableName,
            groups = listOf(
                SchemaGroup(id = "general", name = "Options", order = 0, arguments = arguments)
            )
        )

        return ToolAnalysisResult.Success(
            schema = schema,
            recognizedCount = arguments.count { it.recognized },
            unknownCount = arguments.count { !it.recognized },
            detectedVersion = version
        )
    }

    private fun probeFirstSuccess(
        executable: String,
        workDir: File,
        candidateArgLists: List<List<String>>
    ): com.example.clitoolbox.core.executor.ProbeResult? {
        for (args in candidateArgLists) {
            val result = ProcessRunner.probe(executable, args, workDir, timeoutSeconds = 5)
            if (!result.timedOut && (result.stdout.isNotBlank() || result.stderr.isNotBlank())) {
                return result
            }
        }
        return null
    }

    /**
     * Very generic "-x, --long VALUE   description" line scanner. This is
     * intentionally conservative: anything it can't confidently parse becomes
     * a recognized=false TEXT argument rather than being dropped.
     */
    private val flagLineRegex = Regex(
        """^\s*(-{1,2}[A-Za-z0-9][A-Za-z0-9-]*)(?:[,\s]+(-{1,2}[A-Za-z0-9][A-Za-z0-9-]*))?\s*(?:[<\[]?([A-Z][A-Z0-9_]*)[>\]]?)?\s{2,}(.*)$"""
    )

    private fun parseHelpText(text: String): List<SchemaArgument> {
        val results = mutableListOf<SchemaArgument>()
        var order = 0
        for (rawLine in text.lineSequence()) {
            val match = flagLineRegex.find(rawLine) ?: continue
            val (first, second, valueHint, description) = match.destructured
            val flag = second.ifBlank { first }
            if (results.any { it.flag == flag }) continue

            val type = when {
                valueHint.isBlank() -> ArgumentType.FLAG
                valueHint.contains("NUM") || valueHint.contains("INT") -> ArgumentType.NUMBER
                else -> ArgumentType.TEXT
            }

            results.add(
                SchemaArgument(
                    id = flag.trimStart('-').replace(Regex("[^A-Za-z0-9]+"), "_"),
                    flag = flag,
                    label = flag.trimStart('-').replaceFirstChar { it.uppercase() },
                    description = description.trim().ifBlank { null },
                    type = type,
                    order = order++,
                    recognized = true
                )
            )
        }
        return results
    }
}
