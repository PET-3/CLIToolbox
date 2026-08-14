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
        assertEquals(schema.findArgumentById("input_file")?.flag, restored.findArgumentById("input_file")?.flag)
    }
}
