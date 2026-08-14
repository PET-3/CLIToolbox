package com.example.clitoolbox.core.executor

import java.io.File
import java.util.concurrent.TimeUnit

/** Result of a short, non-streaming process invocation (used by Analyzers). */
data class ProbeResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean
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
        timeoutSeconds: Long = 5
    ): ProbeResult {
        return try {
            val process = ProcessBuilder(listOf(executablePath) + args)
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
                return ProbeResult(null, stdoutBuilder.toString(), stderrBuilder.toString(), timedOut = true)
            }

            outThread.join(1000)
            errThread.join(1000)

            ProbeResult(
                exitCode = process.exitValue(),
                stdout = stdoutBuilder.toString(),
                stderr = stderrBuilder.toString(),
                timedOut = false
            )
        } catch (e: Exception) {
            ProbeResult(exitCode = null, stdout = "", stderr = e.message ?: e.toString(), timedOut = false)
        }
    }
}
