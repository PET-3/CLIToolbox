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
 * from a ToolSchema + the user's current GUI state + any [UnknownArgument]s
 * carried over from a previous Command -> Parser round trip. This is the only
 * place that turns GUI state into a command — the GUI never builds strings
 * itself, and unknown arguments are ALWAYS re-emitted here so they can never
 * silently disappear between Parse and Execute.
 */
object CommandBuilder {

    fun buildArgv(
        schema: ToolSchema,
        state: SchemaState,
        unknownArguments: List<UnknownArgument> = emptyList(),
        flagOrder: List<String> = emptyList()
    ): List<String> {
        val argv = mutableListOf(schema.executable)
        val positionals = mutableMapOf<String, List<String>>()
        val unknownById = unknownArguments.associateBy { it.id }
        val emittedIds = mutableSetOf<String>()

        // Emit flagged items in their original parsed order, when we know it —
        // this is what lets a rebuilt command keep e.g. "-i input.mp4 --custom
        // -c:v libx265" in that shape instead of always moving --custom to the
        // end. Falls back to plain Schema declaration order (the old, and still
        // correct, behavior) when there's no parse history — a fresh GUI-only
        // session never had an original order to preserve.
        if (flagOrder.isNotEmpty()) {
            for (id in flagOrder) {
                if (!emittedIds.add(id)) continue // a repeated flag (e.g. multi-value) only needs its first slot
                val schemaArg = schema.findArgumentById(id)
                if (schemaArg != null) {
                    val value = state[id] ?: continue
                    argumentToTokens(schemaArg, value)?.let { argv.addAll(it) }
                } else {
                    unknownById[id]?.let { argv.addAll(it.toTokens()) }
                }
            }
        }

        // Any flagged Schema argument with a value that flagOrder didn't cover
        // (set via the GUI after parsing, or there was no parse history at all)
        // still needs to be emitted — appended in Schema order.
        for (arg in schema.allArguments().sortedBy { it.order }) {
            if (arg.flag == null) continue // positionals are handled separately below
            if (arg.id in emittedIds) continue
            val value = state[arg.id] ?: continue
            argumentToTokens(arg, value)?.let { argv.addAll(it) }
        }

        // Same for any unknown argument flagOrder didn't cover (shouldn't normally
        // happen — CommandParser always records one — but added manually via the
        // GUI's "+ Add" button never gets a flagOrder entry, so it lands here).
        unknownArguments.forEach { unknownArg ->
            if (unknownArg.id in emittedIds) return@forEach
            argv.addAll(unknownArg.toTokens())
        }

        // Positional (flagless) arguments always come from the Schema, since
        // grouping GUI values (below).
        for (arg in schema.allArguments().sortedBy { it.order }) {
            val value = state[arg.id] ?: continue
            val tokens = argumentToTokens(arg, value) ?: continue
            if (arg.flag == null) {
                positionals[arg.id] = tokens
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

    fun buildCommandString(
        schema: ToolSchema,
        state: SchemaState,
        unknownArguments: List<UnknownArgument> = emptyList(),
        flagOrder: List<String> = emptyList()
    ): String = ShellTokenizer.join(buildArgv(schema, state, unknownArguments, flagOrder))

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
