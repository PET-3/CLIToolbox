package com.example.clitoolbox.command

import com.example.clitoolbox.core.parser.ShellTokenizer
import com.example.clitoolbox.core.schema.ArgumentType
import com.example.clitoolbox.core.schema.SchemaArgument
import com.example.clitoolbox.core.schema.ToolSchema
import java.util.UUID

/**
 * Parses a human-typed (or pasted) command line back into [ParsedCommandState],
 * matched against a ToolSchema. Unknown flags (and any stray positional tokens
 * beyond what the Schema declares) are never discarded — they come back as
 * [ParsedCommandState.unknownArguments] so a later re-generated command
 * (see [CommandBuilder]) still contains them verbatim, and so the GUI can
 * show/edit/delete them.
 */
object CommandParser {

    fun parse(schema: ToolSchema, commandString: String): ParsedCommandState {
        var tokens = ShellTokenizer.tokenize(commandString)
        if (tokens.isNotEmpty() && (tokens.first() == schema.executable || tokens.first().endsWith("/" + schema.executable))) {
            tokens = tokens.drop(1)
        }
        return parseTokens(schema, tokens)
    }

    fun parseTokens(schema: ToolSchema, tokens: List<String>): ParsedCommandState {
        val state = mutableMapOf<String, ArgumentValue>()
        val unknown = mutableListOf<UnknownArgument>()
        // Records the order flagged items (recognized or unknown) were encountered
        // in, so CommandBuilder can preserve original position on rebuild instead
        // of always grouping all recognized flags before all unknown ones.
        val flagOrder = mutableListOf<String>()
        val positionalArgs = schema.allArguments().filter { it.flag == null }.sortedBy { it.order }
        var positionalIndex = 0

        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val arg = schema.findArgumentByFlag(token)

            when {
                arg != null -> {
                    i = consumeMatchedArgument(arg, tokens, i, state)
                    flagOrder.add(arg.id)
                }
                token.startsWith("-") -> {
                    // Joined short flag + value, e.g. "-mx9" or "-ooutput" (used by
                    // tools like 7-Zip) — schema arguments marked joinedWithValue.
                    val joinedPrefixMatch = findJoinedPrefixArgument(schema, token)
                    if (joinedPrefixMatch != null) {
                        val (joinedPrefixArg, remainder) = joinedPrefixMatch
                        state[joinedPrefixArg.id] = valueFromString(joinedPrefixArg, remainder)
                        flagOrder.add(joinedPrefixArg.id)
                        i++
                        continue
                    }

                    // Unrecognized flag — try "--flag=value" joined form against schema first.
                    val eqIndex = token.indexOf('=')
                    val joinedFlag = if (eqIndex > 0) token.substring(0, eqIndex) else null
                    val joinedArg = joinedFlag?.let { schema.findArgumentByFlag(it) }
                    if (joinedArg != null) {
                        state[joinedArg.id] = valueFromString(joinedArg, token.substring(eqIndex + 1))
                        flagOrder.add(joinedArg.id)
                        i++
                    } else {
                        // Group with the following token as its value, unless that token is
                        // itself clearly a flag or is needed to fill a positional slot.
                        val next = tokens.getOrNull(i + 1)
                        if (next != null && !isLikelyFlag(next) && !isNeededForPositional(positionalArgs, positionalIndex, tokens, i)) {
                            val unknownArg = UnknownArgument(newId(), flag = token, value = next)
                            unknown.add(unknownArg)
                            flagOrder.add(unknownArg.id)
                            i += 2
                        } else {
                            val unknownArg = UnknownArgument(newId(), flag = token, value = null)
                            unknown.add(unknownArg)
                            flagOrder.add(unknownArg.id)
                            i++
                        }
                    }
                }
                else -> {
                    // Positional value.
                    val posArg = positionalArgs.getOrNull(positionalIndex)
                    when {
                        posArg == null -> unknown.add(UnknownArgument(newId(), flag = null, value = token))
                        // A variadic positional (FILES / MULTI_SELECT) — assumed to be the
                        // last positional, per common CLI convention (cp/mv/tar/7z-style
                        // "...destination files...") — keeps absorbing bare tokens instead
                        // of only ever capturing the first one.
                        posArg.type == ArgumentType.FILES -> {
                            val existing = (state[posArg.id] as? ArgumentValue.Paths)?.values ?: emptyList()
                            state[posArg.id] = ArgumentValue.Paths(existing + token)
                        }
                        posArg.type == ArgumentType.MULTI_SELECT -> {
                            val existing = (state[posArg.id] as? ArgumentValue.MultiChoice)?.values ?: emptyList()
                            state[posArg.id] = ArgumentValue.MultiChoice(existing + token)
                        }
                        else -> {
                            state[posArg.id] = valueFromString(posArg, token)
                            positionalIndex++
                        }
                    }
                    i++
                }
            }
        }

        return ParsedCommandState(state, unknown, flagOrder)
    }

    /**
     * Conservative guard: if swallowing the next token as this unknown flag's
     * value would leave too few tokens left to fill the remaining (non-variadic)
     * positional slots — required or not — don't do it; let that token fall
     * through to positional matching instead. This is what keeps e.g. a lone
     * trailing "--verbose output.mp4" from having "output.mp4" wrongly treated
     * as --verbose's value instead of the output file.
     */
    private fun isNeededForPositional(
        positionalArgs: List<SchemaArgument>,
        positionalIndex: Int,
        tokens: List<String>,
        currentIndex: Int
    ): Boolean {
        val remainingSlotsNeedingOneToken = positionalArgs.drop(positionalIndex)
            .count { it.type != ArgumentType.FILES && it.type != ArgumentType.MULTI_SELECT }
        val remainingTokensAfterNext = tokens.size - (currentIndex + 2)
        return remainingSlotsNeedingOneToken > 0 && remainingTokensAfterNext < remainingSlotsNeedingOneToken
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

    /**
     * Finds a schema argument whose flag is a joined-value prefix of [token]
     * (e.g. schema flag "-o" against token "-ooutput", or "-mx" against
     * "-mx9"), for arguments explicitly marked [SchemaArgument.joinedWithValue].
     * Picks the longest matching flag so e.g. "-mx" is preferred over a
     * shorter "-m" if both existed. Returns the argument and the remainder
     * (the value) if found.
     */
    private fun findJoinedPrefixArgument(schema: ToolSchema, token: String): Pair<SchemaArgument, String>? {
        return schema.allArguments()
            .filter { it.joinedWithValue && it.flag != null && token.startsWith(it.flag) && token.length > it.flag.length }
            .maxByOrNull { it.flag!!.length }
            ?.let { it to token.removePrefix(it.flag!!) }
    }

    private fun isLikelyFlag(token: String) = token.startsWith("-") && token.length > 1 && !token[1].isDigit()

    private fun newId(): String = UUID.randomUUID().toString().take(8)

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
