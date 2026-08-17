package com.example.clitoolbox.core.executor

import com.example.clitoolbox.core.model.ToolArchitecture
import java.io.File
import java.util.concurrent.TimeUnit

/** Result of a short, non-streaming process invocation (used by Analyzers). */
data class ProbeResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    /**
     * False only when the OS could not start the process at all (e.g.
     * ProcessBuilder.start() itself threw — including Android 10+'s
     * exec-from-app-data-directory rejection, error=13/EACCES). True in
     * every other case, including a timeout, since the process DID start —
     * it just didn't finish in time. This is the reliable signal for "is
     * this binary actually launchable", independent of what it prints or
     * what it exits with.
     */
    val processStarted: Boolean = true
)

/**
 * Runs a short-lived probe command (e.g. `tool --version`) with a timeout and
 * captures stdout/stderr. Used by Analyzers, not by the main Command Executor
 * (which streams output live — see [CommandExecutor]).
 */
object ProcessRunner {

    fun probe(
        executablePath: String,
        args: List<String>,
        workingDir: File,
        timeoutSeconds: Long = 5,
        /**
         * When non-null, argv is wrapped through the system dynamic linker
         * (see [ExecutableLauncher]) so this probe works around Android
         * 10+'s exec-from-app-data restriction, exactly like
         * [CommandExecutor.execute] does for real runs. Left null by tests
         * that intentionally probe a path that was never meant to actually
         * launch (verifying graceful degradation, not real execution).
         */
        architecture: ToolArchitecture? = null
    ): ProbeResult {
        val rawArgv = listOf(executablePath) + args
        val argv = if (architecture != null) ExecutableLauncher.wrap(rawArgv, architecture) else rawArgv

        return try {
            val process = ProcessBuilder(argv)
                .directory(workingDir)
                .redirectErrorStream(false)
                .start()

            val stdoutReader = process.inputStream.bufferedReader()
            val stderrReader = process.errorStream.bufferedReader()

            // Drain streams on separate threads to avoid deadlock on full pipe buffers.
            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()
            val outThread = Thread { stdoutReader.forEachLine { stdoutBuilder.appendLine(it) } }
            val errThread = Thread { stderrReader.forEachLine { stderrBuilder.appendLine(it) } }
            outThread.start()
            errThread.start()

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                outThread.join(500)
                errThread.join(500)
                return ProbeResult(null, stdoutBuilder.toString(), stderrBuilder.toString(), timedOut = true, processStarted = true)
            }

            outThread.join(1000)
            errThread.join(1000)

            ProbeResult(
                exitCode = process.exitValue(),
                stdout = stdoutBuilder.toString(),
                stderr = stderrBuilder.toString(),
                timedOut = false,
                processStarted = true
            )
        } catch (e: Exception) {
            ProbeResult(exitCode = null, stdout = "", stderr = e.message ?: e.toString(), timedOut = false, processStarted = false)
        }
    }
}
