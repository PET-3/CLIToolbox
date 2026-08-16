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
 * Whether an ELF binary is actually expected to *run* on Android, beyond just
 * having a matching CPU ABI. A glibc-linked Linux binary can be arm64-v8a and
 * still fail to launch on Android (which ships Bionic, not glibc) — matching
 * ABI is necessary but not sufficient.
 */
enum class AndroidCompatibility {
    /** Statically linked, or dynamically linked against Android's Bionic linker. */
    COMPATIBLE,
    /** Dynamically linked against glibc (e.g. a standard desktop/server Linux build). */
    LIKELY_INCOMPATIBLE_GLIBC,
    /** Could not be determined from static inspection alone. */
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
    val androidCompatibility: AndroidCompatibility = AndroidCompatibility.UNKNOWN,
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
