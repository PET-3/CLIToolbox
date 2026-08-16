package com.example.clitoolbox.command

import com.example.clitoolbox.core.schema.ArgumentType
import com.example.clitoolbox.core.schema.SchemaArgument
import com.example.clitoolbox.core.schema.SchemaGroup
import com.example.clitoolbox.core.schema.ToolSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    private fun ffmpegLikeSchema() = ToolSchema(
        toolName = "FFmpeg",
        executable = "ffmpeg",
        groups = listOf(
            SchemaGroup("input", "Input", 0, listOf(
                SchemaArgument("input_file", "-i", "Input", type = ArgumentType.FILE, order = 0)
            )),
            SchemaGroup("video", "Video", 1, listOf(
                SchemaArgument("video_codec", "-c:v", "Codec", type = ArgumentType.SELECT, order = 0),
                SchemaArgument("crf", "-crf", "CRF", type = ArgumentType.NUMBER, order = 1)
            )),
            SchemaGroup("output", "Output", 2, listOf(
                SchemaArgument("output_file", null, "Output", type = ArgumentType.FILE, order = 0)
            ))
        ),
        positionalOrder = listOf("output_file")
    )

    @Test
    fun `parses known flags and positional output into schema state`() {
        val schema = ffmpegLikeSchema()
        val result = CommandParser.parse(schema, "ffmpeg -i input.mp4 -c:v libx265 -crf 28 output.mp4")

        assertEquals(ArgumentValue.Path("input.mp4"), result.values["input_file"])
        assertEquals(ArgumentValue.Choice("libx265"), result.values["video_codec"])
        assertEquals(ArgumentValue.Number(28.0), result.values["crf"])
        assertEquals(ArgumentValue.Path("output.mp4"), result.values["output_file"])
        assertTrue(result.unknownArguments.isEmpty())
    }

    @Test
    fun `round trips parse then build back to equivalent argv`() {
        val schema = ffmpegLikeSchema()
        val original = "ffmpeg -i input.mp4 -c:v libx265 -crf 28 output.mp4"
        val parsed = CommandParser.parse(schema, original)
        val rebuilt = CommandBuilder.buildArgv(schema, parsed.values, parsed.unknownArguments)

        assertEquals(
            listOf("ffmpeg", "-i", "input.mp4", "-c:v", "libx265", "-crf", "28", "output.mp4"),
            rebuilt
        )
    }

    @Test
    fun `preserves unknown flag and its value as a grouped UnknownArgument`() {
        val schema = ffmpegLikeSchema()
        val result = CommandParser.parse(schema, "ffmpeg -i input.mp4 --custom-option test -c:v libx265 -crf 28 output.mp4")

        assertEquals(ArgumentValue.Path("input.mp4"), result.values["input_file"])
        assertEquals(ArgumentValue.Choice("libx265"), result.values["video_codec"])
        assertEquals(ArgumentValue.Number(28.0), result.values["crf"])
        assertEquals(ArgumentValue.Path("output.mp4"), result.values["output_file"])

        val unknown = result.unknownArguments.single()
        assertEquals("--custom-option", unknown.flag)
        assertEquals("test", unknown.value)
    }

    @Test
    fun `THE core round-trip requirement - unknown arguments survive Command to Parser to GUI to Command`() {
        // This is the exact scenario the app previously got wrong: an unknown
        // argument recognized by the parser must still be present in the
        // command CommandBuilder regenerates, not silently dropped.
        val schema = ffmpegLikeSchema()
        val original = "ffmpeg -i input.mp4 --custom-option test -c:v libx265 -crf 28 output.mp4"

        val parsed = CommandParser.parse(schema, original)
        val rebuilt = CommandBuilder.buildArgv(schema, parsed.values, parsed.unknownArguments, parsed.flagOrder)

        assertTrue("rebuilt argv must still contain the unknown flag", rebuilt.contains("--custom-option"))
        assertTrue("rebuilt argv must still contain the unknown flag's value", rebuilt.contains("test"))
        // With flagOrder now preserved, this should match the ORIGINAL order exactly,
        // not just be semantically equivalent as a set.
        assertEquals(
            listOf("ffmpeg", "-i", "input.mp4", "--custom-option", "test", "-c:v", "libx265", "-crf", "28", "output.mp4"),
            rebuilt
        )

        // And it must survive a SECOND round trip too (parse the rebuilt command again).
        val reparsed = CommandParser.parse(schema, CommandBuilder.buildCommandString(schema, parsed.values, parsed.unknownArguments, parsed.flagOrder))
        assertEquals(1, reparsed.unknownArguments.size)
        assertEquals("--custom-option", reparsed.unknownArguments.single().flag)
        assertEquals("test", reparsed.unknownArguments.single().value)
    }

    @Test
    fun `unknown argument keeps its ORIGINAL position, not just semantic equivalence`() {
        // Regression test for the position-preservation fix: an unknown flag that
        // appeared BEFORE a recognized flag in the original command must still
        // appear before it after a rebuild — not always shoved to the end.
        val schema = ffmpegLikeSchema()
        val original = "ffmpeg --custom-option test -i input.mp4 -c:v libx265 output.mp4"

        val parsed = CommandParser.parse(schema, original)
        val rebuilt = CommandBuilder.buildArgv(schema, parsed.values, parsed.unknownArguments, parsed.flagOrder)

        assertEquals(
            listOf("ffmpeg", "--custom-option", "test", "-i", "input.mp4", "-c:v", "libx265", "output.mp4"),
            rebuilt
        )
    }

    @Test
    fun `without flagOrder, builder falls back to schema order (backward compatible)`() {
        // A fresh GUI-only session has no parse history — CommandBuilder must
        // still work exactly as it did before flagOrder existed.
        val schema = ffmpegLikeSchema()
        val state = mapOf(
            "input_file" to ArgumentValue.Path("in.mp4"),
            "video_codec" to ArgumentValue.Choice("libx265"),
            "output_file" to ArgumentValue.Path("out.mp4")
        )
        val unknown = listOf(UnknownArgument("u1", "--custom-option", "test"))

        val rebuilt = CommandBuilder.buildArgv(schema, state, unknown) // no flagOrder passed

        assertEquals(
            listOf("ffmpeg", "-i", "in.mp4", "-c:v", "libx265", "--custom-option", "test", "out.mp4"),
            rebuilt
        )
    }

    @Test
    fun `groups a multi-word unknown flag value together as one UnknownArgument`() {
        val schema = ffmpegLikeSchema()
        val result = CommandParser.parse(schema, "ffmpeg -i input.mp4 -vf \"tblend=all_mode=average\" output.mp4")

        assertEquals(ArgumentValue.Path("input.mp4"), result.values["input_file"])
        val unknown = result.unknownArguments.single()
        assertEquals("-vf", unknown.flag)
        assertEquals("tblend=all_mode=average", unknown.value)
    }

    @Test
    fun `lone unknown flag with no value is preserved too`() {
        val schema = ffmpegLikeSchema()
        val result = CommandParser.parse(schema, "ffmpeg -i input.mp4 --verbose output.mp4")

        val unknown = result.unknownArguments.single()
        assertEquals("--verbose", unknown.flag)
        assertEquals(null, unknown.value)
        assertEquals(ArgumentValue.Path("output.mp4"), result.values["output_file"])
    }

    @Test
    fun `handles seek before input flag ordering`() {
        val schema = ToolSchema(
            toolName = "ffmpeg", executable = "ffmpeg",
            groups = listOf(SchemaGroup("input", "Input", 0, listOf(
                SchemaArgument("seek", "-ss", "Seek", type = ArgumentType.TEXT, order = 0),
                SchemaArgument("input_file", "-i", "Input", type = ArgumentType.FILE, order = 1)
            )))
        )
        val result = CommandParser.parse(schema, "ffmpeg -ss 00:00:05 -i input.mp4")
        assertEquals(ArgumentValue.Text("00:00:05"), result.values["seek"])
        assertEquals(ArgumentValue.Path("input.mp4"), result.values["input_file"])
    }
}
