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
}
