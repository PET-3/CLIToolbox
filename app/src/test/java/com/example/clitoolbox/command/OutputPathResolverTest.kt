package com.example.clitoolbox.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputPathResolverTest {

    private fun resolved(template: String, inputPath: String? = null): String {
        val result = OutputPathResolver.resolve(template, inputPath)
        assertTrue("expected Resolved but got $result", result is OutputPathResult.Resolved)
        return (result as OutputPathResult.Resolved).fileName
    }

    private fun rejected(template: String, inputPath: String? = null): String {
        val result = OutputPathResolver.resolve(template, inputPath)
        assertTrue("expected Rejected but got $result", result is OutputPathResult.Rejected)
        return (result as OutputPathResult.Rejected).reason
    }

    @Test
    fun `plain file name is accepted unchanged`() {
        assertEquals("output.mp4", resolved("output.mp4"))
    }

    @Test
    fun `template variables are substituted from the input path`() {
        assertEquals("input_converted.mp4", resolved("{input_stem}_converted.mp4", "/data/media/input.mov"))
        assertEquals("input.mov.bak", resolved("{input_name}.bak", "/data/media/input.mov"))
        assertEquals("out.mov", resolved("out.{input_ext}", "/data/media/input.mov"))
    }

    @Test
    fun `chinese file name is accepted`() {
        assertEquals("转换后的视频.mp4", resolved("转换后的视频.mp4"))
    }

    @Test
    fun `file name with spaces is accepted`() {
        assertEquals("my converted video.mp4", resolved("my converted video.mp4"))
    }

    @Test
    fun `unicode file name is accepted`() {
        assertEquals("vidéo_ünïcödé_🎬.mp4", resolved("vidéo_ünïcödé_🎬.mp4"))
    }

    @Test
    fun `empty file name is rejected`() {
        rejected("")
    }

    @Test
    fun `blank file name is rejected`() {
        rejected("   ")
    }

    @Test
    fun `single dot-dot traversal is rejected`() {
        rejected("../output.mp4")
    }

    @Test
    fun `nested dot-dot traversal is rejected`() {
        rejected("../../etc/output.mp4")
    }

    @Test
    fun `dot-dot in the middle of a path is rejected`() {
        rejected("foo/../../output.mp4")
    }

    @Test
    fun `absolute unix path is rejected`() {
        rejected("/etc/passwd")
    }

    @Test
    fun `absolute windows path is rejected`() {
        rejected("C:\\Windows\\System32\\output.mp4")
    }

    @Test
    fun `any embedded directory separator is rejected, not just traversal`() {
        rejected("subdir/output.mp4")
    }

    @Test
    fun `template substitution that would resolve to traversal is still rejected`() {
        // Guards against a crafted input file name smuggling ".." through a template.
        rejected("{input_stem}/output.mp4", "../../etc/passwd")
    }

    @Test
    fun `control characters are rejected`() {
        rejected("output\u0000.mp4")
    }
}
