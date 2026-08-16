package com.example.clitoolbox.tool

/**
 * Tiny in-memory handoff so "reload" from History actually re-parses the
 * stored command into the tool's GUI state, instead of just opening the tool
 * page with defaults. Set by the History screen, consumed once by
 * ToolDetailScreen on entry, then cleared.
 */
object PendingCommandLoad {
    data class Pending(val toolId: String, val commandString: String)

    var pending: Pending? = null
}
