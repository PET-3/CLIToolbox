package com.example.clitoolbox.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutableNameMatcherTest {

    @Test
    fun `identical names match`() {
        assertTrue(ExecutableNameMatcher.matches("7zz", "7zz"))
    }

    @Test
    fun `exe suffix is ignored`() {
        assertTrue(ExecutableNameMatcher.matches("7zz", "7zz.exe"))
        assertTrue(ExecutableNameMatcher.matches("7zz.exe", "7zz"))
    }

    @Test
    fun `version suffix is ignored`() {
        assertTrue(ExecutableNameMatcher.matches("7zz", "7zz_26"))
        assertTrue(ExecutableNameMatcher.matches("7zz", "7zz-26"))
        assertTrue(ExecutableNameMatcher.matches("ffmpeg", "ffmpeg_6.1"))
    }

    @Test
    fun `case differences are ignored`() {
        assertTrue(ExecutableNameMatcher.matches("FFmpeg", "ffmpeg"))
    }

    @Test
    fun `a full path normalizes to just the file name`() {
        assertTrue(ExecutableNameMatcher.matches("7zz", "/data/data/com.example.clitoolbox/files/tools/abc/bin/7zz"))
    }

    @Test
    fun `genuinely different tools do not match`() {
        assertFalse(ExecutableNameMatcher.matches("7z", "7zz"))
        assertFalse(ExecutableNameMatcher.matches("ffmpeg", "ffprobe"))
    }

    @Test
    fun `matchesAnyOf checks against a known base name set`() {
        assertTrue(ExecutableNameMatcher.matchesAnyOf("7zz_26", setOf("7z", "7za", "7zr", "7zz", "7zzs")))
        assertTrue(ExecutableNameMatcher.matchesAnyOf("7zzs.exe", setOf("7z", "7za", "7zr", "7zz", "7zzs")))
        assertFalse(ExecutableNameMatcher.matchesAnyOf("ffmpeg", setOf("7z", "7za", "7zr", "7zz", "7zzs")))
    }
}
