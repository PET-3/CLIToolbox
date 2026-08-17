package com.example.clitoolbox.core.executor

import com.example.clitoolbox.core.model.ToolArchitecture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.util.concurrent.atomic.AtomicReference

enum class ExecutionState { IDLE, RUNNING, SUCCESS, FAILED, CANCELLED }

sealed class ExecutionEvent {
    data class StateChanged(val state: ExecutionState) : ExecutionEvent()
    data class Stdout(val line: String) : ExecutionEvent()
    data class Stderr(val line: String) : ExecutionEvent()
    data class Finished(val exitCode: Int?, val state: ExecutionState) : ExecutionEvent()
}

/**
 * Executes a Tool's command as a real child process using ProcessBuilder with
 * an explicit argv list (never a shell string), streams stdout/stderr live, and
 * supports cancellation. This is the only place in the app that actually runs
 * a CLI tool.
 */
class CommandExecutor {

    private val activeProcess = AtomicReference<Process?>(null)

    /**
     * @param argv full command, e.g. ["/data/.../ffmpeg", "-i", "in.mp4", "out.mp4"].
     *   Never built by string concatenation/interpolation into a shell command.
     *   argv[0] must be the imported binary's own absolute path — wrapping it
     *   through the system linker (see [ExecutableLauncher]) happens here.
     * @param architecture the imported binary's detected CPU architecture, used
     *   to pick the right system linker and work around Android 10+'s
     *   exec-from-app-data-directory restriction. Pass null only for a binary
     *   known not to live in this app's private storage (there currently isn't
     *   one — every Tool is imported into app-private storage — so real
     *   callers should always pass this).
     */
    fun execute(
        argv: List<String>,
        workingDir: File,
        environment: Map<String, String> = emptyMap(),
        architecture: ToolArchitecture? = null
    ): Flow<ExecutionEvent> = executeInternal(argv, workingDir, environment, architecture).flowOn(Dispatchers.IO)

    private fun executeInternal(
        argv: List<String>,
        workingDir: File,
        environment: Map<String, String>,
        architecture: ToolArchitecture?
    ): Flow<ExecutionEvent> = callbackFlow {
        require(argv.isNotEmpty()) { "argv must not be empty" }

        trySend(ExecutionEvent.StateChanged(ExecutionState.RUNNING))

        // Final protective check, right before actually launching anything:
        // verify the binary genuinely looks executable (exists, is a regular
        // file, is readable, has the executable bit set) so a broken import
        // fails with a clear, specific message here instead of an opaque
        // ProcessBuilder IOException below. This does NOT catch Android
        // 10+'s SELinux exec restriction (canExecute() only reads the POSIX
        // bit, which SELinux ignores) — that's what the linker wrap below is
        // for — but it does catch e.g. the file having been deleted or
        // corrupted since import.
        val binaryFile = File(argv.first())
        val preflightError = when {
            !binaryFile.exists() -> "Tool binary no longer exists at ${binaryFile.absolutePath}."
            !binaryFile.isFile -> "Tool path is not a regular file: ${binaryFile.absolutePath}."
            !binaryFile.canRead() -> "Tool binary is not readable: ${binaryFile.absolutePath}."
            !binaryFile.canExecute() -> "Tool binary is not marked executable: ${binaryFile.absolutePath}."
            else -> null
        }
        if (preflightError != null) {
            trySend(ExecutionEvent.Stderr(preflightError))
            trySend(ExecutionEvent.Finished(null, ExecutionState.FAILED))
            close()
            return@callbackFlow
        }

        // Work around Android 10+'s "exec from app-private-data-directory"
        // SELinux restriction: exec the trusted system linker instead of the
        // app-data binary directly. See ExecutableLauncher's doc for the
        // full explanation. architecture is null only for a binary that
        // (uniquely, currently never the case) isn't in app-private storage.
        val effectiveArgv = if (architecture != null) ExecutableLauncher.wrap(argv, architecture) else argv

        val effectiveEnvironment = if ("LD_LIBRARY_PATH" in environment) environment else
            environment + ("LD_LIBRARY_PATH" to workingDir.absolutePath)

        val process = try {
            ProcessBuilder(effectiveArgv).apply {
                directory(workingDir)
                environment().putAll(effectiveEnvironment)
                redirectErrorStream(false)
            }.start()
        } catch (e: Exception) {
            trySend(ExecutionEvent.Stderr(e.message ?: "Failed to start process"))
            trySend(ExecutionEvent.Finished(null, ExecutionState.FAILED))
            close()
            return@callbackFlow
        }

        activeProcess.set(process)

        val outThread = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { trySend(ExecutionEvent.Stdout(it)) }
            } catch (_: Exception) { /* stream closed on cancel/finish */ }
        }
        val errThread = Thread {
            try {
                process.errorStream.bufferedReader().forEachLine { trySend(ExecutionEvent.Stderr(it)) }
            } catch (_: Exception) { }
        }
        outThread.start()
        errThread.start()

        val exitCode = try {
            process.waitFor()
        } catch (e: InterruptedException) {
            null
        }

        outThread.join(2000)
        errThread.join(2000)

        val finalState = when {
            activeProcess.get() == null -> ExecutionState.CANCELLED
            exitCode == 0 -> ExecutionState.SUCCESS
            else -> ExecutionState.FAILED
        }
        trySend(ExecutionEvent.Finished(exitCode, finalState))
        activeProcess.set(null)
        close()

        awaitClose {
            this@CommandExecutor.cancel()
        }
    }

    /** Cancels the currently running process, if any. Never leaves an orphan process behind. */
    fun cancel() {
        val process = activeProcess.getAndSet(null) ?: return
        if (process.isAlive) {
            process.destroy()
            // Give it a moment to exit cleanly, then force-kill.
            Thread {
                if (process.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS).not()) {
                    process.destroyForcibly()
                }
            }.start()
        }
    }
}
