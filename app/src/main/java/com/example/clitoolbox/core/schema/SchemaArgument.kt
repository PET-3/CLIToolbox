package com.example.clitoolbox.core.schema

/**
 * One parameter of a CLI tool. Produced either by an Analyzer (from --help output)
 * or by hand in the Schema Editor. Arguments an Analyzer could not classify are
 * still emitted as SchemaArgument with [recognized] = false, never dropped.
 */
data class SchemaArgument(
    val id: String,
    /** The literal flag, e.g. "-i", "--codec". Null for positional arguments. */
    val flag: String?,
    val label: String,
    val description: String? = null,
    val type: ArgumentType = ArgumentType.TEXT,
    val required: Boolean = false,
    val defaultValue: String? = null,
    /** Allowed values for SELECT / MULTI_SELECT. */
    val values: List<String> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val order: Int = 0,
    /** True if an Analyzer matched this against known documentation; false = unknown/user-added. */
    val recognized: Boolean = true,
    /** Whether the flag and value are written as one joined token (e.g. -crf28) vs two tokens. */
    val joinedWithValue: Boolean = false,
    /**
     * Marks a FILE-typed argument as an *output* path the user types (with
     * optional {input_name}/{input_stem}/{input_ext} template variables)
     * rather than an existing file picked via Storage Access Framework. The
     * GUI Generator validates these through [com.example.clitoolbox.command.OutputPathResolver]
     * instead of showing a file-browse picker.
     */
    val isOutputPath: Boolean = false,
    /**
     * Optional id of a [com.example.clitoolbox.analyzer.DynamicValueProvider]
     * that produced (or could refresh) this argument's `values`. Purely
     * informational for SELECT/MULTI_SELECT arguments whose value list was
     * generated dynamically at analysis time rather than hardcoded — lets a
     * future re-analysis (or a smarter GUI) know it can re-resolve `values`
     * instead of treating them as fixed.
     */
    val valuesSource: String? = null
)

data class SchemaGroup(
    val id: String,
    val name: String,
    val order: Int = 0,
    val arguments: List<SchemaArgument> = emptyList()
)

/**
 * The full description of how to talk to a Tool: what arguments it accepts, and
 * how to render + build a command from them. This is the single artifact that
 * flows between Analyzer, GUI Generator, Command Builder and Command Parser.
 */
data class ToolSchema(
    val toolName: String,
    val executable: String,
    val groups: List<SchemaGroup> = emptyList(),
    /** Order in which positional (flagless) arguments are appended, by argument id. */
    val positionalOrder: List<String> = emptyList(),
    val schemaVersion: Int = 1
) {
    fun allArguments(): List<SchemaArgument> = groups.flatMap { it.arguments }

    fun findArgumentByFlag(flag: String): SchemaArgument? =
        allArguments().firstOrNull { it.flag == flag }

    fun findArgumentById(id: String): SchemaArgument? =
        allArguments().firstOrNull { it.id == id }
}
