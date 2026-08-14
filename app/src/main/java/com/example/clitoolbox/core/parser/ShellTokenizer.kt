package com.example.clitoolbox.core.parser

/**
 * Splits a human-readable command line into tokens, respecting single and
 * double quotes and backslash escapes, without invoking an actual shell.
 * Used only for display/editing purposes (Command -> tokens -> Schema);
 * actual execution always uses an explicit List<String> argv, never this.
 */
object ShellTokenizer {

    fun tokenize(command: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        var escaping = false
        var hasToken = false

        for (ch in command) {
            when {
                escaping -> {
                    current.append(ch)
                    escaping = false
                    hasToken = true
                }
                ch == '\\' && !inSingle -> escaping = true
                ch == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    hasToken = true
                }
                ch == '"' && !inSingle -> {
                    inDouble = !inDouble
                    hasToken = true
                }
                ch.isWhitespace() && !inSingle && !inDouble -> {
                    if (hasToken) {
                        tokens.add(current.toString())
                        current.clear()
                        hasToken = false
                    }
                }
                else -> {
                    current.append(ch)
                    hasToken = true
                }
            }
        }
        if (hasToken) tokens.add(current.toString())
        return tokens
    }

    /** Re-joins tokens into a human-readable command string, quoting where needed. */
    fun join(tokens: List<String>): String = tokens.joinToString(" ") { token ->
        if (token.isEmpty() || token.any { it.isWhitespace() || it == '"' || it == '\'' }) {
            "\"" + token.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        } else {
            token
        }
    }
}
