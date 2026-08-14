package com.example.clitoolbox.tool

/**
 * Tiny in-memory handoff between ToolDetailScreen and ExecutionScreen so the
 * built argv doesn't need to be serialized through Navigation-Compose string
 * arguments. Cleared once consumed.
 */
object ExecutionSession {
    data class Pending(val toolId: String, val toolName: String, val argv: List<String>, val commandString: String, val workingDir: String)

    var pending: Pending? = null
}
