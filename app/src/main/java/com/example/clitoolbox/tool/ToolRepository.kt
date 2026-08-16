package com.example.clitoolbox.tool

import android.content.Context
import com.example.clitoolbox.core.model.AnalysisSummary
import com.example.clitoolbox.core.model.AndroidCompatibility
import com.example.clitoolbox.core.model.Tool
import com.example.clitoolbox.core.model.ToolArchitecture
import com.example.clitoolbox.core.model.ToolSource
import com.example.clitoolbox.core.schema.SchemaSerializer
import org.json.JSONObject
import java.io.File

/**
 * Persists Tools as JSON under filesDir/tools/<id>/tool.json. Deliberately
 * simple (no Room/DB dependency) so the whole app builds with only the
 * dependencies declared in app/build.gradle.kts.
 */
class ToolRepository(context: Context) {

    private val toolsRoot = File(context.filesDir, "tools").apply { mkdirs() }

    fun listTools(): List<Tool> =
        toolsRoot.listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir -> readToolFile(File(dir, "tool.json")) }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()

    fun getTool(id: String): Tool? = readToolFile(File(toolsRoot, "$id/tool.json"))

    fun saveTool(tool: Tool) {
        val dir = File(toolsRoot, tool.id).apply { mkdirs() }
        val json = JSONObject().apply {
            put("id", tool.id)
            put("name", tool.name)
            put("executableName", tool.executableName)
            put("version", tool.version ?: JSONObject.NULL)
            put("architecture", tool.architecture.name)
            put("androidCompatibility", tool.androidCompatibility.name)
            put("source", tool.source.name)
            put("binaryPath", tool.binaryPath)
            put("schema", tool.schema?.let { SchemaSerializer.toJson(it) } ?: JSONObject.NULL)
            tool.analysisSummary?.let { s ->
                put("analysisSummary", JSONObject().apply {
                    put("totalArguments", s.totalArguments)
                    put("recognizedArguments", s.recognizedArguments)
                    put("unknownArguments", s.unknownArguments)
                    put("succeeded", s.succeeded)
                    put("message", s.message ?: JSONObject.NULL)
                })
            }
            put("createdAt", tool.createdAt)
            put("updatedAt", tool.updatedAt)
        }
        File(dir, "tool.json").writeText(json.toString(2))
    }

    fun deleteTool(id: String): Boolean = File(toolsRoot, id).deleteRecursively()

    private fun readToolFile(file: File): Tool? {
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val summaryJson = json.optJSONObject("analysisSummary")
            Tool(
                id = json.getString("id"),
                name = json.getString("name"),
                executableName = json.getString("executableName"),
                version = if (json.isNull("version")) null else json.optString("version"),
                architecture = runCatching { ToolArchitecture.valueOf(json.getString("architecture")) }
                    .getOrDefault(ToolArchitecture.UNKNOWN),
                androidCompatibility = runCatching { AndroidCompatibility.valueOf(json.optString("androidCompatibility")) }
                    .getOrDefault(AndroidCompatibility.UNKNOWN),
                source = runCatching { ToolSource.valueOf(json.getString("source")) }
                    .getOrDefault(ToolSource.IMPORTED),
                binaryPath = json.getString("binaryPath"),
                schema = json.optJSONObject("schema")?.let { SchemaSerializer.fromJson(it) },
                analysisSummary = summaryJson?.let {
                    AnalysisSummary(
                        totalArguments = it.optInt("totalArguments"),
                        recognizedArguments = it.optInt("recognizedArguments"),
                        unknownArguments = it.optInt("unknownArguments"),
                        succeeded = it.optBoolean("succeeded"),
                        message = if (it.isNull("message")) null else it.optString("message")
                    )
                },
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
            )
        } catch (e: Exception) {
            null
        }
    }
}
