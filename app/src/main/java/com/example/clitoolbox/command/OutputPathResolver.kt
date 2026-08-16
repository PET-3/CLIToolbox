package com.example.clitoolbox.command

import java.io.File

/** Result of resolving a user-supplied output filename against an input path. */
sealed class OutputPathResult {
    data class Resolved(val fileName: String) : OutputPathResult()
    data class Rejected(val reason: String) : OutputPathResult()
}

/**
 * Turns a user-typed output filename (which may contain `{input_name}` /
 * `{input_stem}` / `{input_ext}` template variables) into a safe, final file
 * name, rejecting anything that could escape the target directory. The UI
 * never builds output paths by string concatenation itself — this is the one
 * place that does it, so the traversal checks can't be bypassed by any screen.
 *
 * Only the final path *segment* is ever handed back (no directory component),
 * because CLI Toolbox always writes into a directory it already controls
 * (the tool's working directory or a user-picked SAF destination) — the
 * output name itself must never smuggle in a `/` or `..` to escape that
 * directory.
 */
object OutputPathResolver {

    fun resolve(template: String, inputPath: String?): OutputPathResult {
        if (template.isBlank()) {
            return OutputPathResult.Rejected("Output file name is empty.")
        }

        val substituted = substituteVariables(template, inputPath)

        if (substituted.isBlank()) {
            return OutputPathResult.Rejected("Output file name resolved to an empty string.")
        }

        // Reject absolute paths (Unix and Windows-style) — the output must be a
        // bare file name resolved against a directory this app already controls.
        if (substituted.startsWith("/") || substituted.startsWith("\\") || substituted.matches(Regex("^[A-Za-z]:.*"))) {
            return OutputPathResult.Rejected("Absolute paths are not allowed for output file names.")
        }

        // Reject any path traversal, including through multiple segments
        // ("../", "..\\", or a bare ".." segment anywhere).
        val segments = substituted.split('/', '\\')
        if (segments.any { it == ".." }) {
            return OutputPathResult.Rejected("Path traversal (\"..\") is not allowed in output file names.")
        }
        // Reject any embedded directory separator entirely — output names are a
        // single file name, not a nested path, by design.
        if (segments.size > 1) {
            return OutputPathResult.Rejected("Output file name must not contain a directory separator.")
        }

        // Reject NUL and other control characters that could confuse the filesystem/CLI.
        if (substituted.any { it.code < 0x20 }) {
            return OutputPathResult.Rejected("Output file name contains invalid control characters.")
        }

        return OutputPathResult.Resolved(substituted)
    }

    private fun substituteVariables(template: String, inputPath: String?): String {
        val inputFile = inputPath?.let { File(it) }
        val inputName = inputFile?.name ?: ""
        val inputStem = inputName.substringBeforeLast('.', inputName)
        val inputExt = if (inputName.contains('.')) inputName.substringAfterLast('.') else ""

        return template
            .replace("{input_name}", inputName)
            .replace("{input_stem}", inputStem)
            .replace("{input_ext}", inputExt)
    }
}
