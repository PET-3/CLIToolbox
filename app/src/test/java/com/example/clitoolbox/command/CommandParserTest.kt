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

        assertEquals(ArgumentValue.Path("input.mp4"), result.state["input_file"])
        assertEquals(ArgumentValue.Choice("libx265"), result.state["video_codec"])
        assertEquals(ArgumentValue.Number(28.0), result.state["crf"])
        assertEquals(ArgumentValue.Path("output.mp4"), result.state["output_file"])
        assertTrue(result.unknownTokens.isEmpty())
    }

    @Test
    fun `round trips parse then build back to equivalent argv`() {
        val schema = ffmpegLikeSchema()
        val original = "ffmpeg -i input.mp4 -c:v libx265 -crf 28 output.mp4"
        val parsed = CommandParser.parse(schema, original)
        val rebuilt = CommandBuilder.buildArgv(schema, parsed.state)

        assertEquals(
            listOf("ffmpeg", "-i", "input.mp4", "-c:v", "libx265", "-crf", "28", "output.mp4"),
            rebuilt
        )
    }

    @Test
    fun `preserves unknown flags instead of dropping them`() {
        val schema = ffmpegLikeSchema()
        val result = CommandParser.parse(schema, "ffmpeg -i input.mp4 -vf \"tblend=all_mode=average\" output.mp4")

        assertEquals(ArgumentValue.Path("input.mp4"), result.state["input_file"])
        assertTrue(result.unknownTokens.contains("-vf"))
        assertTrue(result.unknownTokens.any { it.contains("tblend=all_mode=average") })
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
        assertEquals(ArgumentValue.Text("00:00:05"), result.state["seek"])
        assertEquals(ArgumentValue.Path("input.mp4"), result.state["input_file"])
    }
}
