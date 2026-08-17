package com.example.clitoolbox.tool

import com.example.clitoolbox.core.model.AndroidCompatibility
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Exercises ElfInspector.inspectAndroidCompatibility against real, byte-accurate
 * (if minimal) synthetic ELF64 little-endian files — not mocks — so the actual
 * program-header parsing and GLIBC-marker scan run against real bytes.
 */
class ToolCompatibilityTest {

    private fun writeTempElf(bytes: ByteArray): File {
        val file = File.createTempFile("synthetic", ".elf")
        file.writeBytes(bytes)
        file.deleteOnExit()
        return file
    }

    /** Builds a minimal ELF64-LE file with a single PT_INTERP program header naming [interpPath]. */
    private fun buildElfWithInterp(interpPath: String): ByteArray {
        val interpBytes = interpPath.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val headerSize = 64
        val phdrSize = 56
        val interpOffset = headerSize + phdrSize
        val total = interpOffset + interpBytes.size

        val buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        // e_ident
        buffer.put(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        buffer.put(2) // EI_CLASS = 64-bit
        buffer.put(1) // EI_DATA = little-endian
        buffer.put(1) // EI_VERSION
        buffer.put(0) // EI_OSABI
        buffer.position(16)
        buffer.putShort(2) // e_type = ET_EXEC
        buffer.putShort(183) // e_machine = AARCH64
        buffer.putInt(1) // e_version
        buffer.putLong(0) // e_entry
        buffer.putLong(headerSize.toLong()) // e_phoff
        buffer.putLong(0) // e_shoff
        buffer.putInt(0) // e_flags
        buffer.putShort(headerSize.toShort()) // e_ehsize
        buffer.putShort(phdrSize.toShort()) // e_phentsize
        buffer.putShort(1) // e_phnum
        buffer.putShort(0) // e_shentsize
        buffer.putShort(0) // e_shnum
        buffer.putShort(0) // e_shstrndx

        // Elf64_Phdr for PT_INTERP at offset 64
        buffer.position(headerSize)
        buffer.putInt(1) // p_type = PT_INTERP
        buffer.putInt(4) // p_flags
        buffer.putLong(interpOffset.toLong()) // p_offset
        buffer.putLong(0) // p_vaddr
        buffer.putLong(0) // p_paddr
        buffer.putLong(interpBytes.size.toLong()) // p_filesz
        buffer.putLong(interpBytes.size.toLong()) // p_memsz
        buffer.putLong(1) // p_align

        buffer.position(interpOffset)
        buffer.put(interpBytes)

        return buffer.array()
    }

    /** Builds a minimal ELF64-LE file with NO program headers, optionally with trailing junk bytes. */
    private fun buildStaticElf(trailingBytes: ByteArray = ByteArray(0)): ByteArray {
        val headerSize = 64
        val buffer = ByteBuffer.allocate(headerSize + trailingBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        buffer.put(2)
        buffer.put(1)
        buffer.put(1)
        buffer.put(0)
        buffer.position(16)
        buffer.putShort(2)
        buffer.putShort(183)
        buffer.putInt(1)
        buffer.putLong(0)
        buffer.putLong(0) // e_phoff = 0 -> no program headers
        buffer.putLong(0)
        buffer.putInt(0)
        buffer.putShort(headerSize.toShort())
        buffer.putShort(56)
        buffer.putShort(0) // e_phnum = 0
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.position(headerSize)
        buffer.put(trailingBytes)
        return buffer.array()
    }

    @Test
    fun `android bionic linker interpreter is COMPATIBLE`() {
        val file = writeTempElf(buildElfWithInterp("/system/bin/linker64"))
        assertEquals(AndroidCompatibility.COMPATIBLE, ElfInspector.inspectAndroidCompatibility(file))
    }

    @Test
    fun `glibc dynamic linker interpreter is LIKELY_INCOMPATIBLE_GLIBC`() {
        val file = writeTempElf(buildElfWithInterp("/lib64/ld-linux-x86-64.so.2"))
        assertEquals(AndroidCompatibility.LIKELY_INCOMPATIBLE_GLIBC, ElfInspector.inspectAndroidCompatibility(file))
    }

    @Test
    fun `statically linked binary with no interpreter and no glibc markers is COMPATIBLE`() {
        val file = writeTempElf(buildStaticElf())
        assertEquals(AndroidCompatibility.COMPATIBLE, ElfInspector.inspectAndroidCompatibility(file))
    }

    @Test
    fun `glibc symbol version marker without PT_INTERP is still caught`() {
        val junk = "some junk GLIBC_2.17 more junk".toByteArray(Charsets.US_ASCII)
        val file = writeTempElf(buildStaticElf(junk))
        assertEquals(AndroidCompatibility.LIKELY_INCOMPATIBLE_GLIBC, ElfInspector.inspectAndroidCompatibility(file))
    }

    @Test
    fun `truncated file is UNKNOWN rather than guessed COMPATIBLE`() {
        val file = writeTempElf(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        assertEquals(AndroidCompatibility.UNKNOWN, ElfInspector.inspectAndroidCompatibility(file))
    }

    @Test
    fun `ABI matching alone does not imply SUPPORTED - matching arm64-v8a with glibc interpreter is still flagged`() {
        // The exact regression this test guards: an arm64-v8a glibc binary must
        // NOT be reported as compatible just because its CPU architecture matches.
        val file = writeTempElf(buildElfWithInterp("/lib/ld-linux-aarch64.so.1"))
        val elfInfo = ElfInspector.inspect(file)
        assertEquals(com.example.clitoolbox.core.model.ToolArchitecture.ARM64_V8A, elfInfo.architecture)
        assertEquals(AndroidCompatibility.LIKELY_INCOMPATIBLE_GLIBC, ElfInspector.inspectAndroidCompatibility(file))
    }

    // ---- hasDynamicInterpreter — used to decide whether ExecutableLauncher's
    // system-linker workaround can even apply to a given binary -------------

    @Test
    fun `a binary with a PT_INTERP program header is reported as dynamically linked`() {
        val file = writeTempElf(buildElfWithInterp("/system/bin/linker64"))
        assertEquals(true, ElfInspector.hasDynamicInterpreter(file))
    }

    @Test
    fun `a binary with no program headers at all is reported as statically linked`() {
        val file = writeTempElf(buildStaticElf())
        assertEquals(false, ElfInspector.hasDynamicInterpreter(file))
    }

    @Test
    fun `a truncated file is reported as not dynamically linked rather than throwing`() {
        val file = writeTempElf(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        assertEquals(false, ElfInspector.hasDynamicInterpreter(file))
    }

    @Test
    fun `hasDynamicInterpreter is true regardless of which interpreter is named`() {
        // It only answers "is this binary dynamically linked at all", not
        // whether that interpreter is Android- or glibc-flavored — that's a
        // separate question, answered by inspectAndroidCompatibility.
        val glibcFile = writeTempElf(buildElfWithInterp("/lib64/ld-linux-x86-64.so.2"))
        assertEquals(true, ElfInspector.hasDynamicInterpreter(glibcFile))
    }
}
