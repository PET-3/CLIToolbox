package com.example.clitoolbox.core.schema

import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts a [ToolSchema] to/from JSON so it can be exported, shared, imported,
 * and persisted to disk. Uses org.json (bundled with Android) so no extra
 * serialization plugin/dependency is required for the build.
 */
object SchemaSerializer {

    fun toJson(schema: ToolSchema): JSONObject = JSONObject().apply {
        put("schemaVersion", schema.schemaVersion)
        put("tool", JSONObject().apply {
            put("name", schema.toolName)
            put("executable", schema.executable)
        })
        put("positionalOrder", JSONArray(schema.positionalOrder))
        put("groups", JSONArray().apply {
            schema.groups.forEach { group -> put(groupToJson(group)) }
        })
    }

    fun toJsonString(schema: ToolSchema, pretty: Boolean = true): String =
        if (pretty) toJson(schema).toString(2) else toJson(schema).toString()

    private fun groupToJson(group: SchemaGroup): JSONObject = JSONObject().apply {
        put("id", group.id)
        put("name", group.name)
        put("order", group.order)
        put("arguments", JSONArray().apply {
            group.arguments.forEach { arg -> put(argumentToJson(arg)) }
        })
    }

    private fun argumentToJson(arg: SchemaArgument): JSONObject = JSONObject().apply {
        put("id", arg.id)
        put("flag", arg.flag ?: JSONObject.NULL)
        put("label", arg.label)
        put("description", arg.description ?: JSONObject.NULL)
        put("type", arg.type.name.lowercase())
        put("required", arg.required)
        put("defaultValue", arg.defaultValue ?: JSONObject.NULL)
        put("values", JSONArray(arg.values))
        arg.min?.let { put("min", it) }
        arg.max?.let { put("max", it) }
        arg.step?.let { put("step", it) }
        put("order", arg.order)
        put("recognized", arg.recognized)
        put("joinedWithValue", arg.joinedWithValue)
        put("isOutputPath", arg.isOutputPath)
        put("valuesSource", arg.valuesSource ?: JSONObject.NULL)
    }

    fun fromJsonString(text: String): ToolSchema = fromJson(JSONObject(text))

    fun fromJson(json: JSONObject): ToolSchema {
        val toolObj = json.optJSONObject("tool")
        val groupsArr = json.optJSONArray("groups") ?: JSONArray()
        val positionalArr = json.optJSONArray("positionalOrder") ?: JSONArray()

        val groups = (0 until groupsArr.length()).map { i -> groupFromJson(groupsArr.getJSONObject(i)) }
        val positional = (0 until positionalArr.length()).map { i -> positionalArr.getString(i) }

        return ToolSchema(
            toolName = toolObj?.optString("name") ?: "",
            executable = toolObj?.optString("executable") ?: "",
            groups = groups,
            positionalOrder = positional,
            schemaVersion = json.optInt("schemaVersion", 1)
        )
    }

    private fun groupFromJson(json: JSONObject): SchemaGroup {
        val argsArr = json.optJSONArray("arguments") ?: JSONArray()
        val args = (0 until argsArr.length()).map { i -> argumentFromJson(argsArr.getJSONObject(i)) }
        return SchemaGroup(
            id = json.optString("id"),
            name = json.optString("name"),
            order = json.optInt("order", 0),
            arguments = args
        )
    }

    private fun argumentFromJson(json: JSONObject): SchemaArgument {
        val valuesArr = json.optJSONArray("values") ?: JSONArray()
        val values = (0 until valuesArr.length()).map { i -> valuesArr.getString(i) }
        return SchemaArgument(
            id = json.optString("id"),
            flag = if (json.isNull("flag")) null else json.optString("flag"),
            label = json.optString("label"),
            description = if (json.isNull("description")) null else json.optString("description"),
            type = ArgumentType.fromWire(json.optString("type", "text")),
            required = json.optBoolean("required", false),
            defaultValue = if (json.isNull("defaultValue")) null else json.optString("defaultValue"),
            values = values,
            min = if (json.has("min")) json.optDouble("min") else null,
            max = if (json.has("max")) json.optDouble("max") else null,
            step = if (json.has("step")) json.optDouble("step") else null,
            order = json.optInt("order", 0),
            recognized = json.optBoolean("recognized", true),
            joinedWithValue = json.optBoolean("joinedWithValue", false),
            isOutputPath = json.optBoolean("isOutputPath", false),
            valuesSource = if (json.isNull("valuesSource")) null else json.optString("valuesSource").ifBlank { null }
        )
    }
}
