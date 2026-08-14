package com.example.clitoolbox.analyzer

import com.example.clitoolbox.core.executor.ProcessRunner
import com.example.clitoolbox.core.model.Tool
import com.example.clitoolbox.core.schema.ArgumentType
import com.example.clitoolbox.core.schema.SchemaArgument
import com.example.clitoolbox.core.schema.SchemaGroup
import com.example.clitoolbox.core.schema.ToolSchema
import java.io.File

/**
 * Specialized analyzer for FFmpeg. Still only produces a ToolSchema — it does
 * NOT build any FFmpeg-specific UI. It probes `-version`/`-h`/`-formats`/
 * `-codecs` etc. to confirm this really is FFmpeg and to pick up the current
 * build's encoder/decoder list, then emits a curated (but generic) Schema
 * covering the common flags. Anything it doesn't cover still gets picked up
 * as an unknown argument if the user types it in the Command tab.
 */
class FfmpegAnalyzer : GenericAnalyzer() {

    override fun supports(tool: Tool): Boolean {
        val name = tool.executableName.lowercase()
        return name == "ffmpeg" || name.endsWith("/ffmpeg")
    }

    override fun analyze(tool: Tool): ToolAnalysisResult {
        val binary = File(tool.binaryPath)
        val workDir = binary.parentFile ?: File("/")

        val versionProbe = ProcessRunner.probe(binary.absolutePath, listOf("-hide_banner", "-version"), workDir, 5)
        if (versionProbe.timedOut || (versionProbe.stdout.isBlank() && versionProbe.stderr.isBlank())) {
            return ToolAnalysisResult.AnalysisFailed("ffmpeg -version produced no output (timeout or exec failure).")
        }
        val version = versionProbe.stdout.lineSequence().firstOrNull { it.startsWith("ffmpeg version") }
            ?: versionProbe.stdout.lineSequence().firstOrNull()

        val encoders = ProcessRunner.probe(binary.absolutePath, listOf("-hide_banner", "-encoders"), workDir, 5)
        val videoCodecs = extractCodecNames(encoders.stdout, videoOnly = true).ifEmpty {
            listOf("libx264", "libx265", "libvpx-vp9", "libaom-av1", "copy")
        }
        val audioCodecs = extractCodecNames(encoders.stdout, videoOnly = false).ifEmpty {
            listOf("aac", "libopus", "libmp3lame", "copy")
        }

        val inputGroup = SchemaGroup(
            id = "input", name = "Input", order = 0,
            arguments = listOf(
                arg("input_file", "-i", "Input File", ArgumentType.FILE, required = true, order = 0),
                arg("seek_start", "-ss", "Start Time (seek)", ArgumentType.TEXT, order = 1,
                    description = "Position to start from, e.g. 00:00:05"),
                arg("duration", "-t", "Duration", ArgumentType.TEXT, order = 2),
                arg("end_time", "-to", "End Time", ArgumentType.TEXT, order = 3)
            )
        )

        val videoGroup = SchemaGroup(
            id = "video", name = "Video", order = 1,
            arguments = listOf(
                arg("video_codec", "-c:v", "Video Codec", ArgumentType.SELECT, order = 0, values = videoCodecs),
                arg("video_bitrate", "-b:v", "Video Bitrate", ArgumentType.TEXT, order = 1, description = "e.g. 4M, 2500k"),
                arg("crf", "-crf", "CRF (Quality)", ArgumentType.NUMBER, order = 2, min = 0.0, max = 51.0, step = 1.0),
                arg("preset", "-preset", "Preset", ArgumentType.SELECT, order = 3,
                    values = listOf("ultrafast", "superfast", "veryfast", "faster", "fast", "medium", "slow", "slower", "veryslow")),
                arg("framerate", "-r", "Frame Rate", ArgumentType.NUMBER, order = 4),
                arg("resolution", "-s", "Resolution", ArgumentType.TEXT, order = 5, description = "e.g. 1920x1080"),
                arg("no_video", "-vn", "No Video", ArgumentType.FLAG, order = 6)
            )
        )

        val audioGroup = SchemaGroup(
            id = "audio", name = "Audio", order = 2,
            arguments = listOf(
                arg("audio_codec", "-c:a", "Audio Codec", ArgumentType.SELECT, order = 0, values = audioCodecs),
                arg("audio_bitrate", "-b:a", "Audio Bitrate", ArgumentType.TEXT, order = 1, description = "e.g. 192k"),
                arg("no_audio", "-an", "No Audio", ArgumentType.FLAG, order = 2)
            )
        )

        val outputGroup = SchemaGroup(
            id = "output", name = "Output", order = 3,
            arguments = listOf(
                arg("map", "-map", "Stream Map", ArgumentType.TEXT, order = 0),
                arg("format", "-f", "Container Format", ArgumentType.SELECT, order = 1,
                    values = listOf("mp4", "mkv", "mov", "webm", "avi")),
                arg("overwrite", "-y", "Overwrite Output", ArgumentType.FLAG, order = 2, defaultValue = "true"),
                arg("output_file", null, "Output File", ArgumentType.FILE, required = true, order = 3)
            )
        )

        val schema = ToolSchema(
            toolName = tool.name.ifBlank { "FFmpeg" },
            executable = tool.executableName,
            groups = listOf(inputGroup, videoGroup, audioGroup, outputGroup),
            positionalOrder = listOf("output_file")
        )

        val recognized = schema.allArguments().count { it.recognized }
        return ToolAnalysisResult.Success(
            schema = schema,
            recognizedCount = recognized,
            unknownCount = 0,
            detectedVersion = version
        )
    }

    private fun arg(
        id: String, flag: String?, label: String, type: ArgumentType,
        order: Int, required: Boolean = false, values: List<String> = emptyList(),
        min: Double? = null, max: Double? = null, step: Double? = null,
        description: String? = null, defaultValue: String? = null
    ) = SchemaArgument(
        id = id, flag = flag, label = label, description = description, type = type,
        required = required, defaultValue = defaultValue, values = values,
        min = min, max = max, step = step, order = order, recognized = true
    )

    private fun extractCodecNames(encodersOutput: String, videoOnly: Boolean): List<String> {
        // ffmpeg -encoders lines look like: " V..... libx264   H.264 / AVC..."
        val prefix = if (videoOnly) 'V' else 'A'
        val lineRegex = Regex("""^\s*[VAS.][F.][S.][X.][B.][D.]\s+(\S+)\s""")
        return encodersOutput.lineSequence()
            .filter { it.trim().firstOrNull() == prefix }
            .mapNotNull { lineRegex.find(it)?.groupValues?.get(1) }
            .distinct()
            .take(30)
            .toList()
    }
}
