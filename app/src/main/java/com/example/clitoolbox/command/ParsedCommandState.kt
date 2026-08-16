package com.example.clitoolbox.command

/**
 * One argument [CommandParser] could not match against the Schema. Preserved
 * verbatim (never dropped) so it survives a Command -> GUI -> Command round
 * trip. Two shapes:
 *  - an unknown flag with (optionally) the value token that followed it, e.g.
 *    flag="--custom-option", value="test"
 *  - a lone unknown flag with no following value, e.g. flag="--verbose", value=null
 *  - a stray positional token beyond what the Schema declares, e.g. flag=null, value="extra.txt"
 */
data class UnknownArgument(
    val id: String,
    val flag: String?,
    val value: String?
) {
    /** Reconstructs the raw tokens this unknown argument expands back to, in order. */
    fun toTokens(): List<String> = listOfNotNull(flag, value)

    fun displayText(): String = listOfNotNull(flag, value).joinToString(" ")
}

/**
 * Full result of parsing a command string: everything CommandParser recognized
 * against the Schema (-> [values]) plus everything it couldn't place
 * (-> [unknownArguments]). This is the single object that should flow from
 * CommandParser through the GUI and back into CommandBuilder — splitting the
 * two apart (as an earlier version of this app did) is what let unknown
 * arguments silently vanish on rebuild.
 */
data class ParsedCommandState(
    val values: SchemaState,
    val unknownArguments: List<UnknownArgument> = emptyList(),
    /**
     * The order in which flagged items — recognized (by [com.example.clitoolbox.core.schema.SchemaArgument.id])
     * or unknown (by [UnknownArgument.id]) — appeared in the originally parsed
     * command. [CommandBuilder] uses this, when present, to rebuild the command
     * in (close to) its original shape instead of always grouping every
     * recognized flag first and every unknown argument after. Empty for GUI-only
     * state with no parse history — CommandBuilder then falls back to plain
     * Schema declaration order, which is what it always did before this existed.
     * Positional (flagless) arguments are never part of this — they're always
     * placed via [com.example.clitoolbox.core.schema.ToolSchema.positionalOrder].
     */
    val flagOrder: List<String> = emptyList()
)
