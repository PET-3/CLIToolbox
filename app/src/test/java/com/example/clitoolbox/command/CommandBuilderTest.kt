package com.example.clitoolbox.command

import com.example.clitoolbox.core.schema.ArgumentType
import com.example.clitoolbox.core.schema.SchemaArgument
import com.example.clitoolbox.core.schema.SchemaGroup
import com.example.clitoolbox.core.schema.ToolSchema
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandBuilderTest {

    private fun ffmpegLikeSchema() = ToolSchema(
        toolName = "FFmpeg",
        executable = "ffmpeg",
        groups = listOf(
            SchemaGroup("input", "Input", 0, listOf(
                SchemaArgument("input_file", "-i", "Input", type = ArgumentType.FILE, required = true, order = 0)
            )),
            SchemaGroup("video", "Video", 1, listOf(
                SchemaArgument("video_codec", "-c:v", "Codec", type = ArgumentType.SELECT, order = 0),
                SchemaArgument("crf", "-crf", "CRF", type = ArgumentType.NUMBER, order = 1)
            )),
            SchemaGroup("output", "Output", 2, listOf(
                SchemaArgument("output_file", null, "Output", type = ArgumentType.FILE, required = true, order = 0)
            ))
        ),
        positionalOrder = listOf("output_file")
    )

    @Test
    fun `builds argv from schema state in flag then positional order`() {
        val schema = ffmpegLikeSchema()
        val state: SchemaState = mapOf(
            "input_file" to ArgumentValue.Path("input.mp4"),
            "video_codec" to ArgumentValue.Choice("libx265"),
            "crf" to ArgumentValue.Number(28.0),
            "output_file" to ArgumentValue.Path("output.mp4")
        )

        val argv = CommandBuilder.buildArgv(schema, state)

        assertEquals(
            listOf("ffmpeg", "-i", "input.mp4", "-c:v", "libx265", "-crf", "28", "output.mp4"),
            argv
        )
    }

    @Test
    fun `skips blank and unset values`() {
        val schema = ffmpegLikeSchema()
        val state: SchemaState = mapOf(
            "input_file" to ArgumentValue.Path("in.mp4"),
            "output_file" to ArgumentValue.Path("out.mp4")
        )

        val argv = CommandBuilder.buildArgv(schema, state)

        assertEquals(listOf("ffmpeg", "-i", "in.mp4", "out.mp4"), argv)
    }

    @Test
    fun `joined flag renders as single token`() {
        val schema = ToolSchema(
            toolName = "7z", executable = "7z",
            groups = listOf(SchemaGroup("options", "Options", 0, listOf(
                SchemaArgument("compression_level", "-mx", "Level", type = ArgumentType.NUMBER, order = 0, joinedWithValue = true)
            )))
        )
        val argv = CommandBuilder.buildArgv(schema, mapOf("compression_level" to ArgumentValue.Number(9.0)))
        assertEquals(listOf("7z", "-mx9"), argv)
    }

    @Test
    fun `boolean flag argument only appears when true`() {
        val schema = ToolSchema(
            toolName = "ffmpeg", executable = "ffmpeg",
            groups = listOf(SchemaGroup("output", "Output", 0, listOf(
                SchemaArgument("overwrite", "-y", "Overwrite", type = ArgumentType.FLAG, order = 0)
            )))
        )
        val argvTrue = CommandBuilder.buildArgv(schema, mapOf("overwrite" to ArgumentValue.Bool(true)))
        val argvFalse = CommandBuilder.buildArgv(schema, mapOf("overwrite" to ArgumentValue.Bool(false)))
        assertEquals(listOf("ffmpeg", "-y"), argvTrue)
        assertEquals(listOf("ffmpeg"), argvFalse)
    }

    @Test
    fun `unknown arguments are re-emitted after recognized flags but before positionals`() {
        val schema = ffmpegLikeSchema()
        val state: SchemaState = mapOf(
            "input_file" to ArgumentValue.Path("input.mp4"),
            "video_codec" to ArgumentValue.Choice("libx265"),
            "output_file" to ArgumentValue.Path("output.mp4")
        )
        val unknown = listOf(UnknownArgument("u1", flag = "--custom-option", value = "test"))

        val argv = CommandBuilder.buildArgv(schema, state, unknown)

        assertEquals(
            listOf("ffmpeg", "-i", "input.mp4", "-c:v", "libx265", "--custom-option", "test", "output.mp4"),
            argv
        )
    }

    @Test
    fun `unknown argument with no value emits only its flag`() {
        val schema = ffmpegLikeSchema()
        val state: SchemaState = mapOf(
            "input_file" to ArgumentValue.Path("in.mp4"),
            "output_file" to ArgumentValue.Path("out.mp4")
        )
        val unknown = listOf(UnknownArgument("u1", flag = "--verbose", value = null))

        val argv = CommandBuilder.buildArgv(schema, state, unknown)

        assertEquals(listOf("ffmpeg", "-i", "in.mp4", "--verbose", "out.mp4"), argv)
    }

    @Test
    fun `flagOrder preserves original interleaved position of known and unknown flags`() {
        val schema = ffmpegLikeSchema()
        val state: SchemaState = mapOf(
            "input_file" to ArgumentValue.Path("in.mp4"),
            "video_codec" to ArgumentValue.Choice("libx265"),
            "output_file" to ArgumentValue.Path("out.mp4")
        )
        val unknown = listOf(UnknownArgument("u1", flag = "--custom-option", value = "test"))
        // Original order was: unknown, then input_file, then video_codec.
        val flagOrder = listOf("u1", "input_file", "video_codec")

        val argv = CommandBuilder.buildArgv(schema, state, unknown, flagOrder)

        assertEquals(
            listOf("ffmpeg", "--custom-option", "test", "-i", "in.mp4", "-c:v", "libx265", "out.mp4"),
            argv
        )
    }

    @Test
    fun `flagOrder still emits values set via GUI after parsing, appended after the preserved order`() {
        val schema = ffmpegLikeSchema()
        // Simulates: user parsed "-i in.mp4 out.mp4" (flagOrder=[input_file]) then
        // additionally set video_codec via the GUI, which was never in flagOrder.
        val state: SchemaState = mapOf(
            "input_file" to ArgumentValue.Path("in.mp4"),
            "video_codec" to ArgumentValue.Choice("libx265"),
            "output_file" to ArgumentValue.Path("out.mp4")
        )
        val flagOrder = listOf("input_file")

        val argv = CommandBuilder.buildArgv(schema, state, emptyList(), flagOrder)

        assertEquals(listOf("ffmpeg", "-i", "in.mp4", "-c:v", "libx265", "out.mp4"), argv)
    }

    @Test
    fun `a repeated id in flagOrder only uses its first position, not duplicated`() {
        val schema = ffmpegLikeSchema()
        val state: SchemaState = mapOf(
            "input_file" to ArgumentValue.Path("in.mp4"),
            "output_file" to ArgumentValue.Path("out.mp4")
        )
        // Defensive case: flagOrder should never legitimately contain a duplicate,
        // but the builder must not emit the same flag twice if it somehow does.
        val flagOrder = listOf("input_file", "input_file")

        val argv = CommandBuilder.buildArgv(schema, state, emptyList(), flagOrder)

        assertEquals(listOf("ffmpeg", "-i", "in.mp4", "out.mp4"), argv)
    }
}
