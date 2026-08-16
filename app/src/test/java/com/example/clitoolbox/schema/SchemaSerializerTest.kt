package com.example.clitoolbox.schema

import com.example.clitoolbox.core.schema.ArgumentType
import com.example.clitoolbox.core.schema.SchemaArgument
import com.example.clitoolbox.core.schema.SchemaGroup
import com.example.clitoolbox.core.schema.SchemaSerializer
import com.example.clitoolbox.core.schema.ToolSchema
import org.junit.Assert.assertEquals
import org.junit.Test

class SchemaSerializerTest {

    @Test
    fun `round trips schema through json`() {
        val schema = ToolSchema(
            toolName = "FFmpeg",
            executable = "ffmpeg",
            groups = listOf(
                SchemaGroup("input", "Input", 0, listOf(
                    SchemaArgument(
                        id = "input_file", flag = "-i", label = "Input File",
                        description = "The input", type = ArgumentType.FILE,
                        required = true, order = 0
                    ),
                    SchemaArgument(
                        id = "crf", flag = "-crf", label = "CRF", type = ArgumentType.NUMBER,
                        min = 0.0, max = 51.0, step = 1.0, order = 1
                    ),
                    SchemaArgument(
                        id = "output_file", flag = null, label = "Output", type = ArgumentType.FILE,
                        required = true, order = 2, isOutputPath = true
                    ),
                    SchemaArgument(
                        id = "video_codec", flag = "-c:v", label = "Codec", type = ArgumentType.SELECT,
                        values = listOf("libx264", "libx265"), order = 3, valuesSource = "ffmpeg_encoders_video"
                    ),
                    SchemaArgument(
                        id = "compression_level", flag = "-mx", label = "Level", type = ArgumentType.NUMBER,
                        order = 4, joinedWithValue = true
                    ),
                    SchemaArgument(
                        id = "extra", flag = "--custom", label = "Custom", type = ArgumentType.TEXT,
                        order = 5, recognized = false
                    )
                ))
            ),
            positionalOrder = listOf("output_file")
        )

        val json = SchemaSerializer.toJsonString(schema)
        val restored = SchemaSerializer.fromJsonString(json)

        assertEquals(schema.toolName, restored.toolName)
        assertEquals(schema.executable, restored.executable)
        assertEquals(schema.positionalOrder, restored.positionalOrder)
        assertEquals(schema.allArguments().size, restored.allArguments().size)
        assertEquals(schema.findArgumentById("crf")?.min, restored.findArgumentById("crf")?.min)
        assertEquals(schema.findArgumentById("crf")?.max, restored.findArgumentById("crf")?.max)
        assertEquals(schema.findArgumentById("crf")?.step, restored.findArgumentById("crf")?.step)
        assertEquals(schema.findArgumentById("input_file")?.flag, restored.findArgumentById("input_file")?.flag)
        assertEquals(schema.findArgumentById("input_file")?.required, restored.findArgumentById("input_file")?.required)
        assertEquals(schema.findArgumentById("output_file")?.isOutputPath, restored.findArgumentById("output_file")?.isOutputPath)
        assertEquals(true, restored.findArgumentById("output_file")?.isOutputPath)
        assertEquals(schema.findArgumentById("video_codec")?.values, restored.findArgumentById("video_codec")?.values)
        assertEquals("ffmpeg_encoders_video", restored.findArgumentById("video_codec")?.valuesSource)
        assertEquals(schema.findArgumentById("compression_level")?.joinedWithValue, restored.findArgumentById("compression_level")?.joinedWithValue)
        assertEquals(true, restored.findArgumentById("compression_level")?.joinedWithValue)
        assertEquals(false, restored.findArgumentById("extra")?.recognized)
    }
}
