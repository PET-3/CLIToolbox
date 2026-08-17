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

    companion object {
        const val VIDEO_ENCODERS_SOURCE = "ffmpeg_encoders_video"
        const val AUDIO_ENCODERS_SOURCE = "ffmpeg_encoders_audio"

        init {
            // Registered once per process: re-probes the tool's *actual* -encoders
            // output every time it's invoked, rather than freezing a snapshot from
            // whenever analysis first ran. Any future caller (e.g. a "refresh
            // values" action in the Schema Editor) can call
            // DynamicValueProviderRegistry.resolve(argument.valuesSource, tool, argument.values)
            // without needing to know this is FFmpeg-specific.
            DynamicValueProviderRegistry.register(VIDEO_ENCODERS_SOURCE) { tool ->
                queryEncoders(tool, videoOnly = true)
            }
            DynamicValueProviderRegistry.register(AUDIO_ENCODERS_SOURCE) { tool ->
                queryEncoders(tool, videoOnly = false)
            }
        }

        internal fun queryEncoders(tool: Tool, videoOnly: Boolean): List<String> {
            val binary = File(tool.binaryPath)
            val workDir = binary.parentFile ?: File("/")
            val encoders = ProcessRunner.probe(binary.absolutePath, listOf("-hide_banner", "-encoders"), workDir, 5, tool.architecture)
            return extractCodecNames(encoders.stdout, videoOnly)
        }

        /** Visible for testing: parses `ffmpeg -encoders` output without needing a real ffmpeg process. */
        internal fun extractCodecNames(encodersOutput: String, videoOnly: Boolean): List<String> {
            // ffmpeg -encoders lines look like: " V..... libx264   H.264 / AVC..."
            // Note: the captured name must start with a letter, so legend lines
            // like " V..... = Video" (where the "token" after the flags is just
            // "=") are correctly skipped instead of producing a bogus "=" codec.
            val prefix = if (videoOnly) 'V' else 'A'
            val lineRegex = Regex("""^\s*[VAS.][F.][S.][X.][B.][D.]\s+([A-Za-z][A-Za-z0-9_.-]*)\s""")
            return encodersOutput.lineSequence()
                .filter { it.trim().firstOrNull() == prefix }
                .mapNotNull { lineRegex.find(it)?.groupValues?.get(1) }
                .distinct()
                .take(30)
                .toList()
        }
    }

    override fun supports(tool: Tool): Boolean {
        val name = tool.executableName.lowercase()
        return name == "ffmpeg" || name.endsWith("/ffmpeg")
    }

    override fun analyze(tool: Tool): ToolAnalysisResult {
        val binary = File(tool.binaryPath)
        val workDir = binary.parentFile ?: File("/")

        val versionProbe = ProcessRunner.probe(binary.absolutePath, listOf("-hide_banner", "-version"), workDir, 5, tool.architecture)
        if (versionProbe.timedOut || (versionProbe.stdout.isBlank() && versionProbe.stderr.isBlank())) {
            return ToolAnalysisResult.AnalysisFailed("ffmpeg -version produced no output (timeout or exec failure).")
        }
        val version = versionProbe.stdout.lineSequence().firstOrNull { it.startsWith("ffmpeg version") }
            ?: versionProbe.stdout.lineSequence().firstOrNull()

        val staticVideoFallback = listOf("libx264", "libx265", "libvpx-vp9", "libaom-av1", "copy")
        val staticAudioFallback = listOf("aac", "libopus", "libmp3lame", "copy")
        val videoCodecs = DynamicValueProviderRegistry.resolve(VIDEO_ENCODERS_SOURCE, tool, staticVideoFallback)
        val audioCodecs = DynamicValueProviderRegistry.resolve(AUDIO_ENCODERS_SOURCE, tool, staticAudioFallback)

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
                arg("video_codec", "-c:v", "Video Codec", ArgumentType.SELECT, order = 0, values = videoCodecs, valuesSource = VIDEO_ENCODERS_SOURCE),
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
                arg("audio_codec", "-c:a", "Audio Codec", ArgumentType.SELECT, order = 0, values = audioCodecs, valuesSource = AUDIO_ENCODERS_SOURCE),
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
                arg("output_file", null, "Output File", ArgumentType.FILE, required = true, order = 3, isOutputPath = true,
                    description = "File name only — supports {input_name}, {input_stem}, {input_ext}")
            )
        )

        val schema = ToolSchema(
            toolName = tool.name.ifBlank { "FFmpeg" },
            executable = tool.executableName,
            groups = listOfNotNull(inputGroup, videoGroup, audioGroup, outputGroup, buildAdvancedGroup(tool, binary, workDir, inputGroup, videoGroup, audioGroup, outputGroup)),
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

    /**
     * Probes ffmpeg's own `-h full` (falling back to plain `-h`) and folds
     * anything it reveals beyond the curated groups above into an "Advanced"
     * group — so this Analyzer surfaces real capability from the actual binary
     * rather than being permanently limited to a hand-picked flag list. Flags
     * already covered by the curated groups are skipped so there's no
     * duplicate/conflicting SchemaArgument for the same flag.
     */
    private fun buildAdvancedGroup(tool: Tool, binary: File, workDir: File, vararg curatedGroups: SchemaGroup): SchemaGroup? {
        val fullHelp = ProcessRunner.probe(binary.absolutePath, listOf("-hide_banner", "-h", "full"), workDir, 5, tool.architecture)
        val helpText = fullHelp.stdout.ifBlank {
            ProcessRunner.probe(binary.absolutePath, listOf("-hide_banner", "-h"), workDir, 5, tool.architecture).stdout
        }
        val curatedFlags = curatedGroups.flatMap { it.arguments }.mapNotNull { it.flag }.toSet()
        return mergeAdvancedGroup(helpText, curatedFlags)
    }

    /** Visible for testing: the pure merge logic behind [buildAdvancedGroup], without needing a real process. */
    internal fun mergeAdvancedGroup(helpText: String, curatedFlags: Set<String>): SchemaGroup? {
        if (helpText.isBlank()) return null
        val discovered = parseHelpText(helpText)
            .filter { it.flag != null && it.flag !in curatedFlags }
            .distinctBy { it.flag }
            .mapIndexed { index, argument -> argument.copy(order = index) }
        if (discovered.isEmpty()) return null
        return SchemaGroup(id = "advanced", name = "Advanced (from --help)", order = 4, arguments = discovered)
    }

    private fun arg(
        id: String, flag: String?, label: String, type: ArgumentType,
        order: Int, required: Boolean = false, values: List<String> = emptyList(),
        min: Double? = null, max: Double? = null, step: Double? = null,
        description: String? = null, defaultValue: String? = null, isOutputPath: Boolean = false,
        valuesSource: String? = null
    ) = SchemaArgument(
        id = id, flag = flag, label = label, description = description, type = type,
        required = required, defaultValue = defaultValue, values = values,
        min = min, max = max, step = step, order = order, recognized = true, isOutputPath = isOutputPath,
        valuesSource = valuesSource
    )
}
