package com.example.clitoolbox.core.model

/** Where a Tool came from. */
enum class ToolSource {
    IMPORTED,
    BUILTIN
}

/** CPU ABI detected for an imported executable. */
enum class ToolArchitecture {
    ARM64_V8A,
    ARMEABI_V7A,
    X86_64,
    X86,
    UNKNOWN
}

/**
 * A Tool represents any command-line executable the user has imported (or that
 * ships built in). The core system never special-cases a specific tool by name —
 * only a ToolAnalyzer is allowed to know that "ffmpeg" is FFmpeg.
 */
data class Tool(
    val id: String,
    val name: String,
    val executableName: String,
    val version: String? = null,
    val architecture: ToolArchitecture = ToolArchitecture.UNKNOWN,
    val source: ToolSource = ToolSource.IMPORTED,
    /** Absolute path to the executable inside app-private storage. */
    val binaryPath: String,
    val schema: com.example.clitoolbox.core.schema.ToolSchema? = null,
    val analysisSummary: AnalysisSummary? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** Lightweight summary shown on the Tool card / analysis screen. */
data class AnalysisSummary(
    val totalArguments: Int,
    val recognizedArguments: Int,
    val unknownArguments: Int,
    val succeeded: Boolean,
    val message: String? = null
)
