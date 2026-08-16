package com.example.clitoolbox.analyzer

import com.example.clitoolbox.core.model.Tool
import com.example.clitoolbox.core.schema.ArgumentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegAnalyzerTest {

    @Test
    fun `extractCodecNames parses video encoders from ffmpeg -encoders output`() {
        val sample = """
            Encoders:
             V..... = Video
             A..... = Audio
             ------
             V....D libx264              libx264 H.264 / AVC / MPEG-4 AVC (codec h264)
             V....D libx265              libx265 H.265 / HEVC (codec hevc)
             A....D aac                  AAC (Advanced Audio Coding)
             A....D libmp3lame           libmp3lame MP3 (MPEG audio layer 3) (codec mp3)
        """.trimIndent()

        val videoCodecs = FfmpegAnalyzer.extractCodecNames(sample, videoOnly = true)
        val audioCodecs = FfmpegAnalyzer.extractCodecNames(sample, videoOnly = false)

        assertEquals(listOf("libx264", "libx265"), videoCodecs)
        assertEquals(listOf("aac", "libmp3lame"), audioCodecs)
    }

    @Test
    fun `extractCodecNames returns empty list for unparseable input rather than throwing`() {
        assertTrue(FfmpegAnalyzer.extractCodecNames("garbage\nmore garbage", videoOnly = true).isEmpty())
    }

    @Test
    fun `analyze produces a schema covering the required ffmpeg flags even without a real binary`() {
        // No real ffmpeg binary exists at this path in a JVM unit test — analyze()
        // must still degrade gracefully (via ProcessRunner's own exception handling)
        // and produce the curated Schema shape, using static fallback codec lists.
        val tool = Tool(
            id = "t1", name = "FFmpeg", executableName = "ffmpeg",
            binaryPath = "/nonexistent/path/ffmpeg"
        )

        val result = FfmpegAnalyzer().analyze(tool)
        assertTrue(result is ToolAnalysisResult.Success)
        val schema = (result as ToolAnalysisResult.Success).schema

        val flags = schema.allArguments().mapNotNull { it.flag }.toSet()
        val expectedFlags = setOf("-i", "-ss", "-t", "-to", "-c:v", "-b:v", "-crf", "-preset", "-r", "-s", "-vn", "-c:a", "-b:a", "-an", "-map", "-f", "-y")
        assertEquals(expectedFlags, flags)

        val outputArg = schema.findArgumentById("output_file")!!
        assertTrue(outputArg.isOutputPath)
        assertEquals(listOf("output_file"), schema.positionalOrder)

        val videoCodecArg = schema.findArgumentById("video_codec")!!
        assertEquals(ArgumentType.SELECT, videoCodecArg.type)
        assertTrue(videoCodecArg.values.isNotEmpty())
    }

    @Test
    fun `mergeAdvancedGroup discovers flags beyond the curated set from real help text`() {
        val helpText = """
            usage: ffmpeg [options] [[infile options] -i infile]... {[outfile options] outfile}...

            Advanced options:
              -filter_complex FILTER_GRAPH  set filter graph
              -profile:v PROFILE       set video profile
              -level LEVEL              set encoding level
        """.trimIndent()

        val group = FfmpegAnalyzer().mergeAdvancedGroup(helpText, curatedFlags = setOf("-i", "-c:v"))

        assertTrue(group != null)
        val flags = group!!.arguments.mapNotNull { it.flag }.toSet()
        assertEquals(setOf("-filter_complex", "-profile:v", "-level"), flags)
    }

    @Test
    fun `mergeAdvancedGroup excludes flags already covered by curated groups`() {
        val helpText = """
            -i FILE   set input file (already curated, must not be duplicated)
            -crf NUM  set constant rate factor (already curated)
            -newflag FILE   a genuinely new flag
        """.trimIndent()

        val group = FfmpegAnalyzer().mergeAdvancedGroup(helpText, curatedFlags = setOf("-i", "-crf"))

        assertTrue(group != null)
        assertEquals(listOf("-newflag"), group!!.arguments.mapNotNull { it.flag })
    }

    @Test
    fun `mergeAdvancedGroup returns null when there is nothing new to add`() {
        val helpText = "-i FILE   set input file\n"
        assertTrue(FfmpegAnalyzer().mergeAdvancedGroup(helpText, curatedFlags = setOf("-i")) == null)
    }

    @Test
    fun `mergeAdvancedGroup returns null for blank help text rather than throwing`() {
        assertTrue(FfmpegAnalyzer().mergeAdvancedGroup("", curatedFlags = emptySet()) == null)
    }
}
