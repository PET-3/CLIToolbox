package com.example.clitoolbox.analyzer

import com.example.clitoolbox.core.model.Tool
import com.example.clitoolbox.core.schema.ToolSchema

/** Outcome of analyzing a Tool: either a generated schema, or a readable failure. */
sealed class ToolAnalysisResult {
    data class Success(
        val schema: ToolSchema,
        val recognizedCount: Int,
        val unknownCount: Int,
        val detectedVersion: String?
    ) : ToolAnalysisResult()

    data class AnalysisFailed(val reason: String) : ToolAnalysisResult()
}

/**
 * Analyzer contract: Tool -> ToolAnalysisResult -> (Schema).
 * Analyzers are the ONLY place allowed to special-case a specific tool
 * (e.g. "this is ffmpeg"). Everything downstream (GUI, CommandBuilder,
 * Executor) only ever sees a generic ToolSchema.
 */
interface ToolAnalyzer {
    /** Whether this analyzer should handle the given tool (e.g. matches executable name). */
    fun supports(tool: Tool): Boolean

    fun analyze(tool: Tool): ToolAnalysisResult
}
