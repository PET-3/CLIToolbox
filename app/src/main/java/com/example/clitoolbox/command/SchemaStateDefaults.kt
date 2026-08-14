package com.example.clitoolbox.command

import com.example.clitoolbox.core.schema.ArgumentType
import com.example.clitoolbox.core.schema.ToolSchema

/** Builds the initial GUI state from a schema's declared default values. */
object SchemaStateDefaults {

    fun buildDefaults(schema: ToolSchema): SchemaState {
        val state = mutableMapOf<String, ArgumentValue>()
        for (arg in schema.allArguments()) {
            val default = arg.defaultValue ?: continue
            state[arg.id] = when (arg.type) {
                ArgumentType.NUMBER -> ArgumentValue.Number(default.toDoubleOrNull() ?: 0.0)
                ArgumentType.BOOLEAN, ArgumentType.FLAG -> ArgumentValue.Bool(default.equals("true", true))
                ArgumentType.SELECT -> ArgumentValue.Choice(default)
                ArgumentType.MULTI_SELECT -> ArgumentValue.MultiChoice(default.split(","))
                ArgumentType.FILE, ArgumentType.DIRECTORY -> ArgumentValue.Path(default)
                ArgumentType.FILES -> ArgumentValue.Paths(listOf(default))
                ArgumentType.TEXT -> ArgumentValue.Text(default)
            }
        }
        return state
    }
}
