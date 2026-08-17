package com.example.clitoolbox.analyzer

import com.example.clitoolbox.core.executor.ProcessRunner
import com.example.clitoolbox.core.model.Tool
import com.example.clitoolbox.core.model.ToolArchitecture
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

        val version = probeFirstSuccess(binary.absolutePath, workDir, tool.architecture, listOf(
            listOf("--version"), listOf("-version"), listOf("-v")
        ))?.stdout?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()

        val help = probeFirstSuccess(binary.absolutePath, workDir, tool.architecture, listOf(
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
        architecture: ToolArchitecture,
        candidateArgLists: List<List<String>>
    ): com.example.clitoolbox.core.executor.ProbeResult? {
        for (args in candidateArgLists) {
            val result = ProcessRunner.probe(executable, args, workDir, timeoutSeconds = 5, architecture = architecture)
            if (!result.timedOut && (result.stdout.isNotBlank() || result.stderr.isNotBlank())) {
                return result
            }
        }
        return null
    }

    /**
     * Generic help-text line scanner. Recognizes flags across several common
     * conventions without assuming any single CLI's exact style:
     *   --input FILE          (space + ALL-CAPS metavar)
     *   --input <FILE>        (space + bracketed metavar, any case)
     *   --input=FILE          (= joined metavar, any case — unambiguous separator)
     *   -i FILE / -i <FILE>   (short flag, same rules)
     *   -i, --input FILE      (short+long combined via comma)
     * Flag names may contain letters, digits, underscores, hyphens, and colons
     * (e.g. "-filter_complex", "-hide_banner", "-profile:v", "-c:a") — all
     * common in real-world CLI tools (FFmpeg especially uses "_" and ":"
     * heavily), not just the plain-hyphenated subset.
     * A lowercase, unbracketed metavar with no "=" (e.g. "--input file   ...")
     * is intentionally NOT parsed as a value placeholder — there's no reliable
     * way to distinguish that from the first word of the description — so
     * that case is conservatively kept as a FLAG with the full remainder kept
     * as its description, rather than risking a wrong parse. Section headers
     * like "OPTIONS" / "POSITIONAL ARGUMENTS" don't start with "-" so they're
     * naturally skipped rather than mis-parsed.
     */
    private val flagLineRegex = Regex(
        """^\s*(?<flag1>-{1,2}[A-Za-z][A-Za-z0-9_:-]*)(?:,\s*(?<flag2>-{1,2}[A-Za-z][A-Za-z0-9_:-]*))?(?:=(?<eqValue><?[A-Za-z0-9_-]+>?)|\s+(?<spaceValue><[A-Za-z0-9_-]+>|[A-Z][A-Z0-9_-]*))?(?:\t+|\s{2,})(?<description>.*)$"""
    )

    /** Visible for testing: parses generic `--help` text without needing a real process. */
    internal fun parseHelpText(text: String): List<SchemaArgument> {
        val results = mutableListOf<SchemaArgument>()
        var order = 0
        for (rawLine in text.lineSequence()) {
            val match = flagLineRegex.find(rawLine) ?: continue
            val flag = match.groups["flag2"]?.value ?: match.groups["flag1"]!!.value
            if (results.any { it.flag == flag }) continue

            val valueHint = match.groups["eqValue"]?.value ?: match.groups["spaceValue"]?.value
            val description = match.groups["description"]?.value

            val type = when {
                valueHint == null -> ArgumentType.FLAG
                valueHint.contains("NUM", ignoreCase = true) || valueHint.contains("INT", ignoreCase = true) ||
                    valueHint.contains("COUNT", ignoreCase = true) -> ArgumentType.NUMBER
                else -> ArgumentType.TEXT
            }

            results.add(
                SchemaArgument(
                    id = flag.trimStart('-').replace(Regex("[^A-Za-z0-9]+"), "_"),
                    flag = flag,
                    label = flag.trimStart('-').replaceFirstChar { it.uppercase() },
                    description = description?.trim()?.ifBlank { null },
                    type = type,
                    order = order++,
                    recognized = true
                )
            )
        }
        return results
    }
}
