package com.example.clitoolbox.command

import com.example.clitoolbox.core.parser.ShellTokenizer
import com.example.clitoolbox.core.schema.ArgumentType
import com.example.clitoolbox.core.schema.SchemaArgument
import com.example.clitoolbox.core.schema.ToolSchema

/** Current value the user has set for one schema argument in the GUI. */
sealed class ArgumentValue {
    data class Text(val value: String) : ArgumentValue()
    data class Number(val value: Double) : ArgumentValue()
    data class Bool(val value: Boolean) : ArgumentValue()
    data class Choice(val value: String) : ArgumentValue()
    data class MultiChoice(val values: List<String>) : ArgumentValue()
    data class Path(val value: String) : ArgumentValue()
    data class Paths(val values: List<String>) : ArgumentValue()
}

/** The full set of current GUI values, keyed by SchemaArgument.id. */
typealias SchemaState = Map<String, ArgumentValue>

/**
 * Builds an executable argv (List<String>) and a human-readable command string
 * from a ToolSchema + the user's current GUI state. This is the only place that
 * turns GUI state into a command — the GUI never builds strings itself.
 */
object CommandBuilder {

    fun buildArgv(schema: ToolSchema, state: SchemaState): List<String> {
        val argv = mutableListOf(schema.executable)
        val positionals = mutableMapOf<String, List<String>>()

        for (arg in schema.allArguments().sortedBy { it.order }) {
            val value = state[arg.id] ?: continue
            val tokens = argumentToTokens(arg, value) ?: continue

            if (arg.flag == null) {
                positionals[arg.id] = tokens
            } else {
                argv.addAll(tokens)
            }
        }

        // Positional (flagless) arguments are appended in the schema's declared order.
        val positionalIds = schema.positionalOrder.ifEmpty {
            schema.allArguments().filter { it.flag == null }.sortedBy { it.order }.map { it.id }
        }
        for (id in positionalIds) {
            positionals[id]?.let { argv.addAll(it) }
        }

        return argv
    }

    fun buildCommandString(schema: ToolSchema, state: SchemaState): String =
        ShellTokenizer.join(buildArgv(schema, state))

    private fun argumentToTokens(arg: SchemaArgument, value: ArgumentValue): List<String>? {
        val rendered: List<String> = when (value) {
            is ArgumentValue.Text -> if (value.value.isBlank()) return null else listOf(value.value)
            is ArgumentValue.Number -> listOf(formatNumber(value.value))
            is ArgumentValue.Bool -> {
                if (!value.value) return null
                if (arg.type == ArgumentType.FLAG) return arg.flag?.let { listOf(it) }
                emptyList()
            }
            is ArgumentValue.Choice -> if (value.value.isBlank()) return null else listOf(value.value)
            is ArgumentValue.MultiChoice -> if (value.values.isEmpty()) return null else value.values
            is ArgumentValue.Path -> if (value.value.isBlank()) return null else listOf(value.value)
            is ArgumentValue.Paths -> if (value.values.isEmpty()) return null else value.values
        }
        if (rendered.isEmpty() && arg.type != ArgumentType.FLAG) return null

        val flag = arg.flag ?: return rendered

        return if (arg.joinedWithValue && rendered.size == 1) {
            listOf(flag + rendered.first())
        } else {
            listOf(flag) + rendered
        }
    }

    private fun formatNumber(n: Double): String =
        if (n == n.toLong().toDouble()) n.toLong().toString() else n.toString()
}
