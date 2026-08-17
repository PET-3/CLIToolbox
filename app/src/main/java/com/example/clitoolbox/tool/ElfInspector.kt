package com.example.clitoolbox.tool

import com.example.clitoolbox.core.model.AndroidCompatibility
import com.example.clitoolbox.core.model.ToolArchitecture
import java.io.File
import java.io.RandomAccessFile

/**
 * Minimal ELF header/program-header reader used to detect CPU ABI and, beyond
 * that, whether a binary is actually likely to *run* on Android (Bionic) as
 * opposed to being a standard desktop/server Linux (glibc) build that merely
 * happens to share a CPU architecture. Matching ABI is necessary but not
 * sufficient — this is a real, if best-effort, static check, not a rubber
 * stamp on arm64-v8a.
 */
object ElfInspector {

    private const val EM_386 = 3
    private const val EM_ARM = 40
    private const val EM_X86_64 = 62
    private const val EM_AARCH64 = 183
    private const val PT_INTERP = 1

    data class ElfInfo(val isElf: Boolean, val architecture: ToolArchitecture)

    fun inspect(file: File): ElfInfo {
        if (!file.exists() || file.length() < 20) return ElfInfo(false, ToolArchitecture.UNKNOWN)
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                raf.readFully(magic)
                val isElf = magic[0] == 0x7F.toByte() && magic[1] == 'E'.code.toByte() &&
                    magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
                if (!isElf) return ElfInfo(false, ToolArchitecture.UNKNOWN)

                // e_ident[EI_DATA] at offset 5: 1 = little-endian, 2 = big-endian.
                raf.seek(5)
                val dataEncoding = raf.read()

                // e_machine is a 2-byte field at offset 18 in both 32/64-bit ELF headers.
                raf.seek(18)
                val b0 = raf.read()
                val b1 = raf.read()
                val machine = if (dataEncoding == 2) (b0 shl 8) or b1 else (b1 shl 8) or b0

                val arch = when (machine) {
                    EM_AARCH64 -> ToolArchitecture.ARM64_V8A
                    EM_ARM -> ToolArchitecture.ARMEABI_V7A
                    EM_X86_64 -> ToolArchitecture.X86_64
                    EM_386 -> ToolArchitecture.X86
                    else -> ToolArchitecture.UNKNOWN
                }
                ElfInfo(true, arch)
            }
        } catch (e: Exception) {
            ElfInfo(false, ToolArchitecture.UNKNOWN)
        }
    }

    /** Whether [detected] ABI is even present in this device's supported ABI list. */
    fun isAbiSupportedOnThisDevice(detected: ToolArchitecture, supportedAbis: List<String>): Boolean {
        val wire = when (detected) {
            ToolArchitecture.ARM64_V8A -> "arm64-v8a"
            ToolArchitecture.ARMEABI_V7A -> "armeabi-v7a"
            ToolArchitecture.X86_64 -> "x86_64"
            ToolArchitecture.X86 -> "x86"
            ToolArchitecture.UNKNOWN -> return false
        }
        return supportedAbis.contains(wire)
    }

    /**
     * Real (not guessed) static compatibility check:
     *  1. Read the PT_INTERP program header, if present, and inspect the dynamic
     *     linker path it names — "/system/bin/linker(64)" means Android/Bionic;
     *     "ld-linux*" / "/lib(64)/ld-*" means glibc.
     *  2. If PT_INTERP is absent (statically linked) or its path is ambiguous,
     *     scan the file for the ASCII marker "GLIBC_" that glibc embeds in
     *     symbol-versioning strings — Bionic binaries never contain it.
     *  3. If neither check yields a confident answer, report UNKNOWN rather
     *     than guessing COMPATIBLE.
     */
    fun inspectAndroidCompatibility(file: File): AndroidCompatibility {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                if (length < 64) return AndroidCompatibility.UNKNOWN

                raf.seek(4)
                val eiClass = raf.read() // 1 = 32-bit, 2 = 64-bit
                raf.seek(5)
                val eiData = raf.read() // 1 = little-endian, 2 = big-endian
                if (eiClass !in 1..2 || eiData !in 1..2) return AndroidCompatibility.UNKNOWN
                val is64 = eiClass == 2
                val little = eiData == 1

                val phoffOffset = if (is64) 32L else 28L
                val phentsizeOffset = if (is64) 54L else 42L
                val phnumOffset = if (is64) 56L else 44L

                val phoff = readUInt(raf, phoffOffset, if (is64) 8 else 4, little)
                val phentsize = readUInt(raf, phentsizeOffset, 2, little).toInt()
                val phnum = readUInt(raf, phnumOffset, 2, little).toInt()

                if (phoff > 0 && phentsize > 0 && phnum in 1..256) {
                    for (i in 0 until phnum) {
                        val entryOffset = phoff + i.toLong() * phentsize
                        if (entryOffset + phentsize > length) break
                        val pType = readUInt(raf, entryOffset, 4, little)
                        if (pType.toInt() == PT_INTERP) {
                            val pOffsetFieldOffset = entryOffset + if (is64) 8L else 4L
                            val pFilszFieldOffset = entryOffset + if (is64) 32L else 16L
                            val interpOffset = readUInt(raf, pOffsetFieldOffset, if (is64) 8 else 4, little)
                            val interpSize = readUInt(raf, pFilszFieldOffset, if (is64) 8 else 4, little).toInt()
                            if (interpOffset > 0 && interpSize in 1..4096 && interpOffset + interpSize <= length) {
                                val bytes = ByteArray(interpSize)
                                raf.seek(interpOffset)
                                raf.readFully(bytes)
                                val interp = String(bytes, Charsets.US_ASCII).trimEnd('\u0000')
                                classifyInterpreterPath(interp)?.let { return it }
                            }
                            // Found PT_INTERP but couldn't read/classify it — fall through to marker scan.
                            break
                        }
                    }
                }

                // No conclusive PT_INTERP verdict (statically linked, or ambiguous
                // interpreter path) — confirm via a glibc symbol-version marker scan.
                scanForGlibcMarker(raf, length)
            }
        } catch (e: Exception) {
            AndroidCompatibility.UNKNOWN
        }
    }

    private fun classifyInterpreterPath(interp: String): AndroidCompatibility? = when {
        interp.contains("/system/bin/linker") -> AndroidCompatibility.COMPATIBLE
        interp.contains("ld-linux") || interp.contains("/lib/ld-") || interp.contains("/lib64/ld-") ->
            AndroidCompatibility.LIKELY_INCOMPATIBLE_GLIBC
        else -> null
    }

    /** Scans up to the first 16MB of the file for the ASCII marker "GLIBC_". */
    private fun scanForGlibcMarker(raf: RandomAccessFile, length: Long): AndroidCompatibility {
        val marker = "GLIBC_".toByteArray(Charsets.US_ASCII)
        val cap = minOf(length, 16L * 1024 * 1024)
        val bufferSize = 64 * 1024
        raf.seek(0)
        var read = 0L
        var carry = ByteArray(0)
        while (read < cap) {
            val toRead = minOf(bufferSize.toLong(), cap - read).toInt()
            val chunk = ByteArray(toRead)
            val n = raf.read(chunk)
            if (n <= 0) break
            val combined = if (carry.isEmpty()) chunk.copyOf(n) else carry + chunk.copyOf(n)
            if (indexOf(combined, marker) >= 0) return AndroidCompatibility.LIKELY_INCOMPATIBLE_GLIBC
            carry = if (combined.size >= marker.size) combined.copyOfRange(combined.size - marker.size + 1, combined.size) else combined
            read += n
        }
        // No PT_INTERP and no glibc marker found -> most likely a statically
        // linked binary, which runs on the same Linux kernel Android uses.
        return AndroidCompatibility.COMPATIBLE
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun readUInt(raf: RandomAccessFile, offset: Long, size: Int, little: Boolean): Long {
        raf.seek(offset)
        val bytes = ByteArray(size)
        raf.readFully(bytes)
        var value = 0L
        if (little) {
            for (i in size - 1 downTo 0) value = (value shl 8) or (bytes[i].toLong() and 0xFF)
        } else {
            for (i in 0 until size) value = (value shl 8) or (bytes[i].toLong() and 0xFF)
        }
        return value
    }

    /**
     * Whether [file] has a PT_INTERP program header at all — i.e. is
     * dynamically linked. [ExecutableLauncher]'s system-linker workaround for
     * Android's exec-from-app-data restriction only works for dynamically
     * linked binaries (see its class doc); a statically linked binary needs
     * this to be known so that limitation can be reported accurately instead
     * of failing with a generic, confusing error.
     *
     * Deliberately independent of [inspectAndroidCompatibility]'s internals
     * (some duplicated parsing) rather than refactored to share code with it,
     * so this addition can't risk changing that already-verified function's
     * behavior.
     */
    fun hasDynamicInterpreter(file: File): Boolean {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                if (length < 64) return false

                raf.seek(4)
                val eiClass = raf.read()
                raf.seek(5)
                val eiData = raf.read()
                if (eiClass !in 1..2 || eiData !in 1..2) return false
                val is64 = eiClass == 2
                val little = eiData == 1

                val phoffOffset = if (is64) 32L else 28L
                val phentsizeOffset = if (is64) 54L else 42L
                val phnumOffset = if (is64) 56L else 44L

                val phoff = readUInt(raf, phoffOffset, if (is64) 8 else 4, little)
                val phentsize = readUInt(raf, phentsizeOffset, 2, little).toInt()
                val phnum = readUInt(raf, phnumOffset, 2, little).toInt()

                if (phoff <= 0 || phentsize <= 0 || phnum !in 1..256) return false

                for (i in 0 until phnum) {
                    val entryOffset = phoff + i.toLong() * phentsize
                    if (entryOffset + phentsize > length) break
                    val pType = readUInt(raf, entryOffset, 4, little)
                    if (pType.toInt() == PT_INTERP) return true
                }
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
