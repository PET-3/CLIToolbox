package com.example.clitoolbox.core.executor

import com.example.clitoolbox.core.model.ToolArchitecture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExecutableLauncherTest {

    @Test
    fun `arm64 and x86_64 use the 64-bit linker`() {
        assertEquals(ExecutableLauncher.LINKER_64, ExecutableLauncher.linkerPathFor(ToolArchitecture.ARM64_V8A))
        assertEquals(ExecutableLauncher.LINKER_64, ExecutableLauncher.linkerPathFor(ToolArchitecture.X86_64))
    }

    @Test
    fun `armeabi-v7a and x86 use the 32-bit linker`() {
        assertEquals(ExecutableLauncher.LINKER_32, ExecutableLauncher.linkerPathFor(ToolArchitecture.ARMEABI_V7A))
        assertEquals(ExecutableLauncher.LINKER_32, ExecutableLauncher.linkerPathFor(ToolArchitecture.X86))
    }

    @Test
    fun `unknown architecture defaults to the 64-bit linker as a best effort`() {
        assertEquals(ExecutableLauncher.LINKER_64, ExecutableLauncher.linkerPathFor(ToolArchitecture.UNKNOWN))
    }

    @Test
    fun `wrap prepends the linker path ahead of the original argv`() {
        val wrapped = ExecutableLauncher.wrap(listOf("/data/.../bin/7zz", "a", "test.7z", "test.txt"), ToolArchitecture.ARM64_V8A)
        assertEquals(
            listOf("/system/bin/linker64", "/data/.../bin/7zz", "a", "test.7z", "test.txt"),
            wrapped
        )
    }

    @Test
    fun `wrap with 32-bit architecture uses the 32-bit linker`() {
        val wrapped = ExecutableLauncher.wrap(listOf("/data/.../bin/7zz"), ToolArchitecture.ARMEABI_V7A)
        assertEquals(listOf("/system/bin/linker", "/data/.../bin/7zz"), wrapped)
    }

    @Test
    fun `wrap rejects an empty argv`() {
        assertThrows(IllegalArgumentException::class.java) {
            ExecutableLauncher.wrap(emptyList(), ToolArchitecture.ARM64_V8A)
        }
    }
}
