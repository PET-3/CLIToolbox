package com.example.clitoolbox.core.executor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ProcessRunnerTest {

    @Test
    fun `processStarted is false when the process could never be launched`() {
        // A genuinely nonexistent path makes ProcessBuilder.start() throw —
        // exactly the same failure shape as Android's exec-from-app-data
        // rejection (error=13) would produce, from this class's point of view.
        val result = ProcessRunner.probe("/nonexistent/path/does-not-exist", emptyList(), File("."), timeoutSeconds = 2)

        assertFalse("a process that never started must report processStarted = false", result.processStarted)
        assertNull(result.exitCode)
        assertFalse(result.timedOut)
    }

    @Test
    fun `probe without an architecture does not wrap argv through any linker`() {
        // Regression guard: passing architecture = null (the default) must
        // behave exactly as before this feature existed — direct argv, no
        // /system/bin/linker* prepended — since that path doesn't exist on a
        // plain JVM test host and every existing analyzer test relies on
        // this default behavior.
        val result = ProcessRunner.probe("/nonexistent/plain-path", emptyList(), File("."), timeoutSeconds = 2)
        assertFalse(result.processStarted)
        // If wrapping had been applied, the error would reference a linker
        // path instead of our plain nonexistent path.
        assertEquals(false, result.stderr.contains("linker"))
    }
}
