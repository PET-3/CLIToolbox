package com.example.clitoolbox.core.schema

/** Controls which Compose widget the GUI Generator renders for an argument. */
enum class ArgumentType {
    TEXT,
    NUMBER,
    BOOLEAN,
    SELECT,
    MULTI_SELECT,
    FILE,
    FILES,
    DIRECTORY,
    FLAG;

    companion object {
        fun fromWire(value: String): ArgumentType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: TEXT
    }
}
