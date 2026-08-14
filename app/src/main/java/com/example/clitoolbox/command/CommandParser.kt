package com.example.clitoolbox.command

import com.example.clitoolbox.core.parser.ShellTokenizer
import com.example.clitoolbox.core.schema.ArgumentType
import com.example.clitoolbox.core.schema.SchemaArgument
import com.example.clitoolbox.core.schema.ToolSchema

/** Result of parsing a raw command string against a ToolSchema. */
data class CommandParseResult(
    val state: SchemaState,
    /** Raw tokens that did not match any known flag in the schema — preserved, never dropped. */
    val unknownTokens: List<String>
)

/**
 * Parses a human-typed (or pasted) command line back into GUI state, matched
 * against a ToolSchema. Unknown flags are never discarded — they're kept in
 * [CommandParseResult.unknownTokens] so a later re-generated command still
 * contains them, and so the Schema Editor can promote them into real arguments.
 */
object CommandParser {

    fun parse(schema: ToolSchema, commandString: String): CommandParseResult {
        var tokens = ShellTokenizer.tokenize(commandString)
        if (tokens.isNotEmpty() && (tokens.first() == schema.executable || tokens.first().endsWith("/" + schema.executable))) {
            tokens = tokens.drop(1)
        }
        return parseTokens(schema, tokens)
    }

    fun parseTokens(schema: ToolSchema, tokens: List<String>): CommandParseResult {
        val state = mutableMapOf<String, ArgumentValue>()
        val unknown = mutableListOf<String>()
        val positionalArgs = schema.allArguments().filter { it.flag == null }.sortedBy { it.order }
        var positionalIndex = 0

        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val arg = schema.findArgumentByFlag(token)

            when {
                arg != null -> {
                    i = consumeMatchedArgument(arg, tokens, i, state)
                }
                token.startsWith("-") -> {
                    // Unrecognized flag — try "--flag=value" joined form against schema.
                    val eqIndex = token.indexOf('=')
                    val joinedFlag = if (eqIndex > 0) token.substring(0, eqIndex) else null
                    val joinedArg = joinedFlag?.let { schema.findArgumentByFlag(it) }
                    if (joinedArg != null) {
                        state[joinedArg.id] = valueFromString(joinedArg, token.substring(eqIndex + 1))
                        i++
                    } else {
                        unknown.add(token)
                        i++
                    }
                }
                else -> {
                    // Positional value.
                    val posArg = positionalArgs.getOrNull(positionalIndex)
                    if (posArg != null) {
                        state[posArg.id] = valueFromString(posArg, token)
                        positionalIndex++
                    } else {
                        unknown.add(token)
                    }
                    i++
                }
            }
        }

        return CommandParseResult(state, unknown)
    }

    private fun consumeMatchedArgument(
        arg: SchemaArgument,
        tokens: List<String>,
        index: Int,
        state: MutableMap<String, ArgumentValue>
    ): Int {
        return when (arg.type) {
            ArgumentType.FLAG, ArgumentType.BOOLEAN -> {
                state[arg.id] = ArgumentValue.Bool(true)
                index + 1
            }
            ArgumentType.MULTI_SELECT, ArgumentType.FILES -> {
                val next = tokens.getOrNull(index + 1)
                if (next != null && !isLikelyFlag(next)) {
                    val existing = (state[arg.id] as? ArgumentValue.MultiChoice)?.values ?: emptyList()
                    state[arg.id] = ArgumentValue.MultiChoice(existing + next)
                    index + 2
                } else {
                    index + 1
                }
            }
            else -> {
                val next = tokens.getOrNull(index + 1)
                if (next != null) {
                    state[arg.id] = valueFromString(arg, next)
                    index + 2
                } else {
                    index + 1
                }
            }
        }
    }

    private fun isLikelyFlag(token: String) = token.startsWith("-") && token.length > 1 && !token[1].isDigit()

    private fun valueFromString(arg: SchemaArgument, raw: String): ArgumentValue = when (arg.type) {
        ArgumentType.NUMBER -> ArgumentValue.Number(raw.toDoubleOrNull() ?: 0.0)
        ArgumentType.BOOLEAN, ArgumentType.FLAG -> ArgumentValue.Bool(raw == "1" || raw.equals("true", true) || raw.isEmpty())
        ArgumentType.SELECT -> ArgumentValue.Choice(raw)
        ArgumentType.MULTI_SELECT -> ArgumentValue.MultiChoice(raw.split(","))
        ArgumentType.FILE, ArgumentType.DIRECTORY -> ArgumentValue.Path(raw)
        ArgumentType.FILES -> ArgumentValue.Paths(listOf(raw))
        ArgumentType.TEXT -> ArgumentValue.Text(raw)
    }
}
