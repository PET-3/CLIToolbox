package com.example.clitoolbox.tool

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(
    val id: String,
    val toolId: String,
    val toolName: String,
    val commandString: String,
    val timestamp: Long,
    val result: String, // SUCCESS / FAILED / CANCELLED
    val outputFile: String? = null
)

/** Append-only execution history, persisted as a single JSON array file. */
class HistoryRepository(context: Context) {

    private val file = File(context.filesDir, "history.json")

    fun list(): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryEntry(
                    id = o.getString("id"),
                    toolId = o.getString("toolId"),
                    toolName = o.getString("toolName"),
                    commandString = o.getString("commandString"),
                    timestamp = o.getLong("timestamp"),
                    result = o.getString("result"),
                    outputFile = if (o.isNull("outputFile")) null else o.optString("outputFile")
                )
            }.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(entry: HistoryEntry) {
        val current = list().toMutableList()
        current.add(0, entry)
        val arr = JSONArray()
        current.take(200).forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("toolId", e.toolId)
                put("toolName", e.toolName)
                put("commandString", e.commandString)
                put("timestamp", e.timestamp)
                put("result", e.result)
                put("outputFile", e.outputFile ?: JSONObject.NULL)
            })
        }
        file.writeText(arr.toString())
    }
}
