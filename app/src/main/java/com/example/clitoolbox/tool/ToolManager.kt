package com.example.clitoolbox.tool

import android.content.Context
import android.net.Uri
import com.example.clitoolbox.analyzer.AnalyzerRegistry
import com.example.clitoolbox.analyzer.ToolAnalysisResult
import com.example.clitoolbox.core.model.AnalysisSummary
import com.example.clitoolbox.core.model.Tool

sealed class ImportAndAnalyzeResult {
    data class Success(val tool: Tool) : ImportAndAnalyzeResult()
    data class ImportFailed(val reason: String) : ImportAndAnalyzeResult()
    data class UnsupportedArchitecture(val architecture: com.example.clitoolbox.core.model.ToolArchitecture) : ImportAndAnalyzeResult()
    data class IncompatibleRuntime(val reason: String) : ImportAndAnalyzeResult()
    data class AnalysisFailed(val tool: Tool, val reason: String) : ImportAndAnalyzeResult()
}

/**
 * Orchestrates the Tool -> Analyzer -> Schema flow end to end: import a
 * binary, run the right Analyzer, persist the resulting Tool+Schema.
 */
class ToolManager(context: Context) {

    private val importer = ToolImporter(context)
    private val repository = ToolRepository(context)

    fun repo(): ToolRepository = repository

    fun importAndAnalyze(uri: Uri, displayName: String?): ImportAndAnalyzeResult {
        return when (val importResult = importer.importFromUri(uri, displayName)) {
            is ImportResult.Failure -> ImportAndAnalyzeResult.ImportFailed(importResult.reason)
            is ImportResult.UnsupportedArchitecture -> ImportAndAnalyzeResult.UnsupportedArchitecture(importResult.architecture)
            is ImportResult.IncompatibleRuntime -> ImportAndAnalyzeResult.IncompatibleRuntime(importResult.reason)
            is ImportResult.Success -> {
                val analyzed = analyzeAndSave(importResult.tool)
                analyzed
            }
        }
    }

    fun reanalyze(tool: Tool): ImportAndAnalyzeResult = analyzeAndSave(tool)

    private fun analyzeAndSave(tool: Tool): ImportAndAnalyzeResult {
        return try {
            when (val result = AnalyzerRegistry.analyze(tool)) {
                is ToolAnalysisResult.Success -> {
                    val updated = tool.copy(
                        version = result.detectedVersion ?: tool.version,
                        schema = result.schema,
                        analysisSummary = AnalysisSummary(
                            totalArguments = result.recognizedCount + result.unknownCount,
                            recognizedArguments = result.recognizedCount,
                            unknownArguments = result.unknownCount,
                            succeeded = true
                        ),
                        updatedAt = System.currentTimeMillis()
                    )
                    repository.saveTool(updated)
                    ImportAndAnalyzeResult.Success(updated)
                }
                is ToolAnalysisResult.AnalysisFailed -> {
                    val updated = tool.copy(
                        analysisSummary = AnalysisSummary(0, 0, 0, succeeded = false, message = result.reason),
                        updatedAt = System.currentTimeMillis()
                    )
                    repository.saveTool(updated)
                    ImportAndAnalyzeResult.AnalysisFailed(updated, result.reason)
                }
            }
        } catch (e: Exception) {
            // Analyzer must never crash the app — always degrade to a readable failure.
            val updated = tool.copy(
                analysisSummary = AnalysisSummary(0, 0, 0, succeeded = false, message = e.message ?: e.toString())
            )
            repository.saveTool(updated)
            ImportAndAnalyzeResult.AnalysisFailed(updated, e.message ?: e.toString())
        }
    }

    fun delete(toolId: String): Boolean = repository.deleteTool(toolId)
}
