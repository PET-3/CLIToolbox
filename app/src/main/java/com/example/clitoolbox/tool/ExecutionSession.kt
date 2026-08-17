package com.example.clitoolbox.tool

import com.example.clitoolbox.core.model.ToolArchitecture

/**
 * Tiny in-memory handoff between ToolDetailScreen and ExecutionScreen so the
 * built argv doesn't need to be serialized through Navigation-Compose string
 * arguments. Cleared once consumed.
 */
object ExecutionSession {
    data class Pending(
        val toolId: String,
        val toolName: String,
        val argv: List<String>,
        val commandString: String,
        val workingDir: String,
        /** Needed to pick the right system linker for Android's exec-from-app-data workaround. */
        val architecture: ToolArchitecture
    )

    var pending: Pending? = null
}
