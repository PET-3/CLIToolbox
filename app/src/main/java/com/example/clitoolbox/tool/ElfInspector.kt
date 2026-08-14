package com.example.clitoolbox.tool

import com.example.clitoolbox.core.model.ToolArchitecture
import java.io.File
import java.io.RandomAccessFile

/** Minimal ELF header reader used only to detect CPU ABI of an imported binary. */
object ElfInspector {

    private const val EM_386 = 3
    private const val EM_ARM = 40
    private const val EM_X86_64 = 62
    private const val EM_AARCH64 = 183

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

    /** Whether [detected] can actually run on this device (compares against Build.SUPPORTED_ABIS). */
    fun isSupportedOnThisDevice(detected: ToolArchitecture, supportedAbis: List<String>): Boolean {
        val wire = when (detected) {
            ToolArchitecture.ARM64_V8A -> "arm64-v8a"
            ToolArchitecture.ARMEABI_V7A -> "armeabi-v7a"
            ToolArchitecture.X86_64 -> "x86_64"
            ToolArchitecture.X86 -> "x86"
            ToolArchitecture.UNKNOWN -> return false
        }
        return supportedAbis.contains(wire)
    }
}
