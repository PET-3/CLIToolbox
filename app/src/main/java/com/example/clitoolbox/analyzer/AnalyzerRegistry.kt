package com.example.clitoolbox.analyzer

import com.example.clitoolbox.core.model.Tool

/**
 * Picks the right Analyzer for a Tool. Specialized analyzers are tried first
 * (most specific first); GenericAnalyzer is always the fallback so every tool
 * gets *some* schema, even if unrecognized.
 */
object AnalyzerRegistry {

    private val specialized: List<ToolAnalyzer> = listOf(
        FfmpegAnalyzer(),
        SevenZipAnalyzer()
    )

    private val fallback: ToolAnalyzer = GenericAnalyzer()

    fun analyzerFor(tool: Tool): ToolAnalyzer =
        specialized.firstOrNull { it.supports(tool) } ?: fallback

    fun analyze(tool: Tool): ToolAnalysisResult = analyzerFor(tool).analyze(tool)
}
