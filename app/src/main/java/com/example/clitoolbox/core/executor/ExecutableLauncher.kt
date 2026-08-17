package com.example.clitoolbox.core.executor

import com.example.clitoolbox.core.model.ToolArchitecture

/**
 * Android 10+ (API 29+) enforces a W^X SELinux policy: apps cannot invoke
 * execve() directly on a file inside their own private data directory —
 * "Execution of files from the writable app home directory is a W^X
 * violation" (https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission).
 * This is enforced by the kernel/SELinux regardless of Unix permission bits —
 * `chmod 755` / `File.setExecutable(true)` both "succeed" at the POSIX level
 * while the OS still refuses to actually launch the file, surfacing as
 * `Cannot run program "...": error=13, Permission denied` from
 * `ProcessBuilder.start()`. This is documented Android platform behavior,
 * not a bug in this app, and no amount of chmod can bypass it.
 *
 * The workaround — the same technique Termux uses (see termux-exec's
 * "system linker execution") — is to never have the kernel exec() the
 * untrusted app-data file directly. Instead, exec the *trusted* system
 * dynamic linker (`/system/bin/linker` / `/system/bin/linker64`, which
 * SELinux permits since it carries the system linker's own file context)
 * and pass our binary to it as an argument. The linker then loads and runs
 * it itself — the kernel never sees a fresh execve() on the app-data file,
 * so the SELinux rule never triggers.
 *
 * Important limitation (also true for Termux): this only works for
 * *dynamically linked* binaries — one with a PT_INTERP program header. A
 * fully statically linked binary doesn't go through the dynamic linker at
 * all, so this workaround cannot apply to it; see
 * [com.example.clitoolbox.tool.ElfInspector.hasDynamicInterpreter].
 */
object ExecutableLauncher {

    const val LINKER_32 = "/system/bin/linker"
    const val LINKER_64 = "/system/bin/linker64"

    /** Which system linker to use for a given detected binary architecture. */
    fun linkerPathFor(architecture: ToolArchitecture): String = when (architecture) {
        ToolArchitecture.ARM64_V8A, ToolArchitecture.X86_64 -> LINKER_64
        ToolArchitecture.ARMEABI_V7A, ToolArchitecture.X86 -> LINKER_32
        // Best-effort default for an undetermined architecture — most modern
        // devices are 64-bit, and if this guess is wrong the resulting
        // ProcessBuilder failure is still reported clearly, not silently
        // swallowed.
        ToolArchitecture.UNKNOWN -> LINKER_64
    }

    /**
     * Wraps [argv] (whose first element must be the app-private binary's
     * *absolute* path — the linker requires an absolute path) so the kernel
     * execs the trusted system linker instead of the app-data file directly.
     * Result: `[linkerPath, argv[0], argv[1], ...]`.
     */
    fun wrap(argv: List<String>, architecture: ToolArchitecture): List<String> {
        require(argv.isNotEmpty()) { "argv must not be empty" }
        return listOf(linkerPathFor(architecture)) + argv
    }
}
