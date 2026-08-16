package com.example.clitoolbox.analyzer

import com.example.clitoolbox.core.schema.ArgumentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericAnalyzerTest {

    private val analyzer = GenericAnalyzer()

    @Test
    fun `parses ALL-CAPS space-separated metavar`() {
        val help = "  -i FILE  Input file to read\n"
        val args = analyzer.parseHelpText(help)
        val arg = args.single()
        assertEquals("-i", arg.flag)
        assertEquals(ArgumentType.TEXT, arg.type)
        assertEquals("Input file to read", arg.description)
    }

    @Test
    fun `parses bracketed metavar of any case`() {
        val help = "  --output <file>  Where to write results\n"
        val args = analyzer.parseHelpText(help)
        val arg = args.single()
        assertEquals("--output", arg.flag)
        assertEquals(ArgumentType.TEXT, arg.type)
    }

    @Test
    fun `parses equals-joined metavar`() {
        val help = "  --input=FILE  Input file\n"
        val args = analyzer.parseHelpText(help)
        val arg = args.single()
        assertEquals("--input", arg.flag)
        assertEquals(ArgumentType.TEXT, arg.type)
    }

    @Test
    fun `parses short and long combined flag via comma`() {
        val help = "  -i, --input FILE   Input file\n"
        val args = analyzer.parseHelpText(help)
        val arg = args.single()
        // The long form is preferred when both are present.
        assertEquals("--input", arg.flag)
    }

    @Test
    fun `flag with no value becomes a FLAG type argument`() {
        val help = "  -v, --verbose  Enable verbose output\n"
        val args = analyzer.parseHelpText(help)
        val arg = args.single()
        assertEquals(ArgumentType.FLAG, arg.type)
        assertEquals("Enable verbose output", arg.description)
    }

    @Test
    fun `numeric hint maps to NUMBER type`() {
        val help = "  --retries NUM  Number of retries\n"
        val args = analyzer.parseHelpText(help)
        assertEquals(ArgumentType.NUMBER, args.single().type)
    }

    @Test
    fun `section headers are ignored, not mis-parsed as flags`() {
        val help = """
            USAGE: tool [OPTIONS] <command>

            OPTIONS:
              -i FILE  Input file
              -o FILE  Output file

            POSITIONAL ARGUMENTS:
              command  The command to run
        """.trimIndent()
        val args = analyzer.parseHelpText(help)
        assertEquals(setOf("-i", "-o"), args.map { it.flag }.toSet())
    }

    @Test
    fun `duplicate flags across multiple lines are not duplicated`() {
        val help = "  -i FILE  Input\n  -i FILE  Input (again)\n"
        val args = analyzer.parseHelpText(help)
        assertEquals(1, args.size)
    }

    @Test
    fun `unparseable lines are simply skipped, not crashing`() {
        val help = "this is just prose with no flags at all\n\n\n"
        val args = analyzer.parseHelpText(help)
        assertTrue(args.isEmpty())
    }

    @Test
    fun `flag names with underscores and colons are fully matched, not truncated`() {
        // Real-world tools use these heavily (ffmpeg especially: -hide_banner,
        // -filter_complex, -profile:v, -c:a) — a narrower character class would
        // silently truncate the flag or fail to match the line at all.
        val help = "  -filter_complex GRAPH  apply a filter graph\n  -profile:v PROFILE     set video profile\n"
        val args = analyzer.parseHelpText(help)
        assertEquals(setOf("-filter_complex", "-profile:v"), args.map { it.flag }.toSet())
    }
}
