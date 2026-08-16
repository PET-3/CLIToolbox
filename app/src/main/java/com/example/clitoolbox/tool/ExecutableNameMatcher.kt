package com.example.clitoolbox.tool

/**
 * Loose identity matching for executable names. Two real gaps this exists to
 * close:
 *
 *  1. A Tool's stored [com.example.clitoolbox.core.model.Tool.executableName]
 *     doesn't always come out as the "clean" name a user expects — Storage
 *     Access Framework can hand back a display name with an OS-added
 *     disambiguation suffix ("7zz (1)", "7zz_1"), or the user may have
 *     literally imported a version-suffixed build ("7zz_26"). When that
 *     happens, an exact-string check against a known binary name (in
 *     [com.example.clitoolbox.analyzer.SevenZipAnalyzer.supports]) or against
 *     what the user typed in the Command tab (in [com.example.clitoolbox.command.CommandParser])
 *     silently fails — which is what let a tool's own executable name end up
 *     misclassified as an "unknown argument" instead of being recognized and
 *     stripped.
 *  2. Platform naming conventions vary: "7zz" vs "7zz.exe", different case.
 *
 * [normalize] strips a path, lowercases, drops a trailing .exe/.bin
 * extension, and drops one trailing version-ish suffix (`_26`, `-26`,
 * `_v2`, `-2.1`, ...). [matches] compares two names after normalizing both.
 */
object ExecutableNameMatcher {

    private val versionSuffix = Regex("""[_-]v?\d+(\.\d+)*$""")

    fun normalize(raw: String): String {
        var name = raw.substringAfterLast('/').substringAfterLast('\\').trim().lowercase()
        name = name.removeSuffix(".exe").removeSuffix(".bin")
        name = name.replace(versionSuffix, "")
        return name
    }

    fun matches(a: String, b: String): Boolean = normalize(a) == normalize(b)

    /** True if [executableName] normalizes to one of [knownBaseNames] (already-normalized, lowercase). */
    fun matchesAnyOf(executableName: String, knownBaseNames: Set<String>): Boolean =
        normalize(executableName) in knownBaseNames
}
