package com.example.clitoolbox.analyzer

import com.example.clitoolbox.command.ArgumentValue
import com.example.clitoolbox.command.CommandBuilder
import com.example.clitoolbox.command.CommandParser
import com.example.clitoolbox.core.model.Tool
import com.example.clitoolbox.core.schema.ArgumentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SevenZipAnalyzerTest {

    private fun schema() = (SevenZipAnalyzer().analyze(
        Tool(id = "t1", name = "7-Zip", executableName = "7zz", binaryPath = "/nonexistent/7zz")
    ) as ToolAnalysisResult.Success).schema

    @Test
    fun `schema models the action as a required select positional`() {
        val commandArg = schema().findArgumentById("command")!!
        assertEquals(ArgumentType.SELECT, commandArg.type)
        assertTrue(commandArg.required)
        assertEquals(listOf("a", "x", "e", "l", "t"), commandArg.values)
    }

    @Test
    fun `schema covers the required 7-zip switches`() {
        val flags = schema().allArguments().mapNotNull { it.flag }.toSet()
        assertEquals(setOf("-o", "-p", "-mx", "-r", "-y", "-t", "-sdel"), flags)
    }

    @Test
    fun `add command round-trips through parser and builder, capturing all input files`() {
        val s = schema()
        val parsed = CommandParser.parse(s, "7zz a archive.7z file1.txt file2.txt")
        assertEquals(ArgumentValue.Choice("a"), parsed.values["command"])
        assertEquals(ArgumentValue.Path("archive.7z"), parsed.values["archive"])
        assertEquals(ArgumentValue.Paths(listOf("file1.txt", "file2.txt")), parsed.values["input_files"])
        assertTrue(parsed.unknownArguments.isEmpty())

        val argv = CommandBuilder.buildArgv(s, parsed.values, parsed.unknownArguments, parsed.flagOrder)
        assertEquals("7zz", argv.first())
        assertTrue(argv.contains("a"))
        assertTrue(argv.contains("archive.7z"))
        assertTrue(argv.contains("file1.txt"))
        assertTrue(argv.contains("file2.txt"))
    }

    @Test
    fun `extract with output dir uses joined -o flag`() {
        val s = schema()
        val parsed = CommandParser.parse(s, "7zz x archive.7z -ooutput")
        val outputDir = parsed.values["output_dir"]
        assertEquals(ArgumentValue.Path("output"), outputDir)

        val argv = CommandBuilder.buildArgv(s, parsed.values, parsed.unknownArguments, parsed.flagOrder)
        assertTrue(argv.contains("-ooutput"))
    }

    @Test
    fun `list and test commands parse the action and archive only`() {
        val s = schema()
        val listParsed = CommandParser.parse(s, "7zz l archive.7z")
        assertEquals(ArgumentValue.Choice("l"), listParsed.values["command"])
        assertEquals(ArgumentValue.Path("archive.7z"), listParsed.values["archive"])

        val testParsed = CommandParser.parse(s, "7zz t archive.7z")
        assertEquals(ArgumentValue.Choice("t"), testParsed.values["command"])
    }

    // ---- Explicit regression tests for the "executable itself ends up in
    // Unknown Arguments" bug report -------------------------------------

    @Test
    fun testParseSevenZipAdd() {
        val s = schema()
        val parsed = CommandParser.parse(s, "7zz a test.7z test.txt")

        assertEquals(ArgumentValue.Choice("a"), parsed.values["command"])
        assertEquals(ArgumentValue.Path("test.7z"), parsed.values["archive"])
        assertEquals(ArgumentValue.Paths(listOf("test.txt")), parsed.values["input_files"])
        assertTrue("executable and every token must be recognized", parsed.unknownArguments.isEmpty())
    }

    @Test
    fun testParseSevenZipExtract() {
        val s = schema()
        val withPaths = CommandParser.parse(s, "7zz x test.7z -ooutput")
        assertEquals(ArgumentValue.Choice("x"), withPaths.values["command"])
        assertEquals(ArgumentValue.Path("test.7z"), withPaths.values["archive"])
        assertEquals(ArgumentValue.Path("output"), withPaths.values["output_dir"])
        assertTrue(withPaths.unknownArguments.isEmpty())

        val flat = CommandParser.parse(s, "7zz e test.7z -ooutput")
        assertEquals(ArgumentValue.Choice("e"), flat.values["command"])
        assertEquals(ArgumentValue.Path("output"), flat.values["output_dir"])
    }

    @Test
    fun testParseSevenZipList() {
        val s = schema()
        val parsed = CommandParser.parse(s, "7zz l test.7z")
        assertEquals(ArgumentValue.Choice("l"), parsed.values["command"])
        assertEquals(ArgumentValue.Path("test.7z"), parsed.values["archive"])
        assertTrue(parsed.unknownArguments.isEmpty())
    }

    @Test
    fun testParseSevenZipTest() {
        val s = schema()
        val parsed = CommandParser.parse(s, "7zz t test.7z")
        assertEquals(ArgumentValue.Choice("t"), parsed.values["command"])
        assertEquals(ArgumentValue.Path("test.7z"), parsed.values["archive"])
        assertTrue(parsed.unknownArguments.isEmpty())
    }

    @Test
    fun testParseMultipleInputFiles() {
        val s = schema()
        val parsed = CommandParser.parse(s, "7zz a test.7z a.txt b.txt c.txt")

        assertEquals(ArgumentValue.Choice("a"), parsed.values["command"])
        assertEquals(ArgumentValue.Path("test.7z"), parsed.values["archive"])
        assertEquals(ArgumentValue.Paths(listOf("a.txt", "b.txt", "c.txt")), parsed.values["input_files"])
        assertTrue("b.txt/c.txt must not fall into unknownArguments", parsed.unknownArguments.isEmpty())
    }

    @Test
    fun testParseJoinedOutputOption() {
        val s = schema()

        // "-ooutput" (no separator)
        assertEquals(ArgumentValue.Path("output"), CommandParser.parse(s, "7zz x test.7z -ooutput").values["output_dir"])

        // "-mx=9" (= separator) must not leave a leading "=" in the value
        val mxParsed = CommandParser.parse(s, "7zz a test.7z test.txt -mx=9")
        assertEquals(ArgumentValue.Number(9.0), mxParsed.values["compression_level"])

        // "-p123456" (no separator) for password
        val pParsed = CommandParser.parse(s, "7zz a test.7z test.txt -p123456")
        assertEquals(ArgumentValue.Text("123456"), pParsed.values["password"])

        // Separate-token forms ("-o output", "-p 123456", "-mx 9") already work
        // via ordinary exact-flag matching since the schema stores the bare flag.
        val separateForm = CommandParser.parse(s, "7zz x test.7z -o output")
        assertEquals(ArgumentValue.Path("output"), separateForm.values["output_dir"])
    }

    @Test
    fun testExecutableIsNotUnknownArgument() {
        // Covers the exact reported bug: the executable token must never end
        // up in unknownArguments — including when the Tool's stored
        // executableName isn't a byte-for-byte match of what the user typed
        // (SAF-assigned suffixes, version-suffixed binaries like "7zz_26", a
        // ".exe" extension, case differences).
        val exactNameSchema = schema()
        val exactParsed = CommandParser.parse(exactNameSchema, "7zz a test.7z test.txt")
        assertTrue(exactParsed.unknownArguments.none { it.value == "7zz" || it.flag == "7zz" })

        val versionSuffixedTool = Tool(id = "t2", name = "7-Zip", executableName = "7zz_26", binaryPath = "/nonexistent/7zz_26")
        assertTrue("SevenZipAnalyzer must still claim a version-suffixed binary name", SevenZipAnalyzer().supports(versionSuffixedTool))
        val versionSuffixedSchema = (SevenZipAnalyzer().analyze(versionSuffixedTool) as ToolAnalysisResult.Success).schema
        val versionParsed = CommandParser.parse(versionSuffixedSchema, "7zz a test.7z test.txt")
        assertTrue(
            "typing the short name '7zz' must still be recognized against a Tool stored as '7zz_26'",
            versionParsed.unknownArguments.none { it.value == "7zz" || it.flag == "7zz" }
        )
        assertEquals(ArgumentValue.Choice("a"), versionParsed.values["command"])

        val exeSuffixedTool = Tool(id = "t3", name = "7-Zip", executableName = "7zz.exe", binaryPath = "/nonexistent/7zz.exe")
        assertTrue("SevenZipAnalyzer must still claim a .exe-suffixed binary name", SevenZipAnalyzer().supports(exeSuffixedTool))
        val exeSchema = (SevenZipAnalyzer().analyze(exeSuffixedTool) as ToolAnalysisResult.Success).schema
        val exeParsed = CommandParser.parse(exeSchema, "7zz.exe a test.7z test.txt")
        assertTrue(exeParsed.unknownArguments.isEmpty())
    }

    @Test
    fun testUnknownSevenZipArgumentIsPreserved() {
        val s = schema()
        val parsed = CommandParser.parse(s, "7zz a test.7z test.txt --custom-option hello")

        assertEquals(ArgumentValue.Choice("a"), parsed.values["command"])
        assertEquals(ArgumentValue.Path("test.7z"), parsed.values["archive"])
        assertEquals(ArgumentValue.Paths(listOf("test.txt")), parsed.values["input_files"])

        val unknown = parsed.unknownArguments.single()
        assertEquals("--custom-option", unknown.flag)
        assertEquals("hello", unknown.value)

        // CommandBuilder must not drop it on rebuild.
        val argv = CommandBuilder.buildArgv(s, parsed.values, parsed.unknownArguments, parsed.flagOrder)
        assertTrue(argv.contains("--custom-option"))
        assertTrue(argv.contains("hello"))
    }

    @Test
    fun testBuildSevenZipCommand() {
        val s = schema()
        val state = mapOf(
            "command" to ArgumentValue.Choice("a"),
            "archive" to ArgumentValue.Path("test.7z"),
            "input_files" to ArgumentValue.Paths(listOf("test.txt"))
        )

        val argv = CommandBuilder.buildArgv(s, state)

        assertEquals(listOf("7zz", "a", "test.7z", "test.txt"), argv)
    }
}
