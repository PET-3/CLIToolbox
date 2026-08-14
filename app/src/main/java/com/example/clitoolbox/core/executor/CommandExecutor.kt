package com.example.clitoolbox.core.executor

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
     */
    fun execute(argv: List<String>, workingDir: File, environment: Map<String, String> = emptyMap()): Flow<ExecutionEvent> = executeInternal(argv, workingDir, environment).flowOn(Dispatchers.IO)

    private fun executeInternal(argv: List<String>, workingDir: File, environment: Map<String, String>): Flow<ExecutionEvent> = callbackFlow {
        require(argv.isNotEmpty()) { "argv must not be empty" }

        trySend(ExecutionEvent.StateChanged(ExecutionState.RUNNING))

        val process = try {
            ProcessBuilder(argv).apply {
                directory(workingDir)
                environment().putAll(environment)
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
