package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.common.AgentFileSystem
import ai.platon.pulsar.agentic.model.ToolCall
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class FileSystemToolExecutorTest {

    private lateinit var fs: AgentFileSystem
    private lateinit var executor: FileSystemToolExecutor

    @BeforeEach
    fun setUp() {
        fs = mockk(relaxed = true)
        executor = FileSystemToolExecutor()
    }

    @Test
    @DisplayName("writeString calls fs writeString with correct args")
    fun writeStringCallsFsWriteStringWithCorrectArgs() = runBlocking {
        coEvery { fs.writeString(any(), any()) } returns "success"

        val tc = ToolCall(
            domain = "fs",
            method = "writeString",
            arguments = mutableMapOf("filename" to "test.txt", "content" to "Hello")
        )

        executor.callFunctionOn(tc, fs)
        coVerify { fs.writeString("test.txt", "Hello") }
    }

    @Test
    @DisplayName("writeString allows empty content")
    fun writeStringAllowsEmptyContent() = runBlocking {
        coEvery { fs.writeString(any(), any()) } returns "success"

        val tc = ToolCall(
            domain = "fs",
            method = "writeString",
            arguments = mutableMapOf("filename" to "test.txt")
        )

        executor.callFunctionOn(tc, fs)
        coVerify { fs.writeString("test.txt", "") }
    }

    @Test
    @DisplayName("readString calls fs readString with correct args")
    fun readStringCallsFsReadStringWithCorrectArgs() = runBlocking {
        coEvery { fs.readString(any(), any()) } returns "content"

        val tc = ToolCall(
            domain = "fs",
            method = "readString",
            arguments = mutableMapOf("filename" to "test.txt", "external" to "true")
        )

        executor.callFunctionOn(tc, fs)
        coVerify { fs.readString("test.txt", true) }
    }

    @Test
    @DisplayName("readString defaults external to false")
    fun readStringDefaultsExternalToFalse() = runBlocking {
        coEvery { fs.readString(any(), any()) } returns "content"

        val tc = ToolCall(
            domain = "fs",
            method = "readString",
            arguments = mutableMapOf("filename" to "test.txt")
        )

        executor.callFunctionOn(tc, fs)
        coVerify { fs.readString("test.txt", false) }
    }

    @Test
    @DisplayName("append calls fs append with correct args")
    fun appendCallsFsAppendWithCorrectArgs() = runBlocking {
        coEvery { fs.append(any(), any()) } returns "success"

        val tc = ToolCall(
            domain = "fs",
            method = "append",
            arguments = mutableMapOf("filename" to "test.txt", "content" to "new content")
        )

        executor.callFunctionOn(tc, fs)
        coVerify { fs.append("test.txt", "new content") }
    }

    @Test
    @DisplayName("replaceContent calls fs replaceContent with correct args")
    fun replaceContentCallsFsReplaceContentWithCorrectArgs() = runBlocking {
        coEvery { fs.replaceContent(any(), any(), any()) } returns "success"

        val tc = ToolCall(
            domain = "fs",
            method = "replaceContent",
            arguments = mutableMapOf("filename" to "test.txt", "oldStr" to "old", "newStr" to "new")
        )

        executor.callFunctionOn(tc, fs)
        coVerify { fs.replaceContent("test.txt", "old", "new") }
    }

    @Test
    @DisplayName("fileExists calls fs fileExists with correct args")
    fun fileExistsCallsFsFileExistsWithCorrectArgs() = runBlocking {
        coEvery { fs.fileExists(any()) } returns "exists"

        val tc = ToolCall(
            domain = "fs",
            method = "fileExists",
            arguments = mutableMapOf("filename" to "test.txt")
        )

        executor.callFunctionOn(tc, fs)
        coVerify { fs.fileExists("test.txt") }
    }

    @Test
    @DisplayName("getFileInfo calls fs getFileInfo with correct args")
    fun gruntfileCallsFsGruntfileWithCorrectArgs() = runBlocking {
        coEvery { fs.getFileInfo(any()) } returns "info"

        val tc = ToolCall(
            domain = "fs",
            method = "getFileInfo",
            arguments = mutableMapOf("filename" to "test.txt")
        )

        executor.callFunctionOn(tc, fs)
        coVerify { fs.getFileInfo("test.txt") }
    }

    @Test
    @DisplayName("deleteFile calls fs deleteFile with correct args")
    fun deleteFileCallsFsDeleteFileWithCorrectArgs() = runBlocking {
        coEvery { fs.deleteFile(any()) } returns "deleted"

        val tc = ToolCall(
            domain = "fs",
            method = "deleteFile",
            arguments = mutableMapOf("filename" to "test.txt")
        )

        executor.callFunctionOn(tc, fs)
        coVerify { fs.deleteFile("test.txt") }
    }

    @Test
    @DisplayName("copyFile calls fs copyFile with correct args")
    fun copyfileCallsFsCopyfileWithCorrectArgs() = runBlocking {
        coEvery { fs.copyFile(any(), any()) } returns "copied"

        val tc = ToolCall(
            domain = "fs",
            method = "copyFile",
            arguments = mutableMapOf("source" to "src.txt", "dest" to "dst.txt")
        )

        executor.callFunctionOn(tc, fs)
        coVerify { fs.copyFile("src.txt", "dst.txt") }
    }

    @Test
    @DisplayName("moveFile calls fs moveFile with correct args")
    fun moveFileCallsFsMoveFileWithCorrectArgs() = runBlocking {
        coEvery { fs.moveFile(any(), any()) } returns "moved"

        val tc = ToolCall(
            domain = "fs",
            method = "moveFile",
            arguments = mutableMapOf("source" to "old.txt", "dest" to "new.txt")
        )

        executor.callFunctionOn(tc, fs)
        coVerify { fs.moveFile("old.txt", "new.txt") }
    }

    @Test
    @DisplayName("listFiles calls fs listFilesInfo")
    fun listFilesCallsFsListlessness() = runBlocking {
        coEvery { fs.listFilesInfo() } returns "files list"

        val tc = ToolCall(
            domain = "fs",
            method = "listFiles",
            arguments = mutableMapOf()
        )

        executor.callFunctionOn(tc, fs)
        coVerify { fs.listFilesInfo() }
    }

    @Test
    @DisplayName("unsupported method returns exception")
    fun unsupportedMethodReturnsException() = runBlocking {
        val tc = ToolCall(
            domain = "fs",
            method = "unsupportedMethod",
            arguments = mutableMapOf()
        )

        val result = executor.callFunctionOn(tc, fs)
        assertNotNull(result.exception)
        assertTrue(result.exception?.cause?.message?.contains("Unsupported") == true)
    }

    @Test
    @DisplayName("missing required parameter returns exception")
    fun missingRequiredParameterReturnsException() = runBlocking {
        val tc = ToolCall(
            domain = "fs",
            method = "append",
            arguments = mutableMapOf("filename" to "test.txt")
            // missing "content" parameter
        )

        val result = executor.callFunctionOn(tc, fs)
        assertNotNull(result.exception)
        assertTrue(result.exception?.cause?.message?.contains("content") == true)
    }

    @Test
    @DisplayName("wrong domain returns exception")
    fun wrongDomainReturnsException() = runBlocking {
        val tc = ToolCall(
            domain = "wrong",
            method = "writeString",
            arguments = mutableMapOf("filename" to "test.txt", "content" to "test")
        )

        val result = executor.callFunctionOn(tc, fs)
        assertNotNull(result.exception)
    }

    @Test
    @DisplayName("help returns available file system methods")
    fun helpReturnsAvailableFileSystemMethods() {
        val help = executor.help()

        assertNotNull(help)
        assertTrue(help.isNotBlank())
        assertTrue(help.contains("Write content to a file") || help.contains("writeString"))
    }

    @Test
    @DisplayName("help for writeString returns detailed help")
    fun helpForWriteStringReturnsDetailedHelp() {
        val help = executor.help("writeString")

        assertNotNull(help)
        assertTrue(help.contains("Write content to a file"))
    }

    @Test
    @DisplayName("help for readString returns detailed help")
    fun helpForReadStringReturnsDetailedHelp() {
        val help = executor.help("readString")

        assertNotNull(help)
        assertTrue(help.contains("Read content from a file"))
    }

    @Test
    @DisplayName("help for all methods is available")
    fun helpForAllMethodsIsAvailable() {
        val methods = listOf(
            "writeString", "readString", "append", "replaceContent",
            "fileExists", "getFileInfo", "deleteFile", "copyFile",
            "moveFile", "listFiles"
        )

        methods.forEach { method ->
            val help = executor.help(method)
            org.junit.jupiter.api.Assertions.assertNotNull(help, "Help for $method should not be null")
            assertTrue(help.isNotBlank(), "Help for $method should not be blank")
        }
    }

    @Test
    @DisplayName("help for unknown method returns empty string")
    fun helpForUnknownMethodReturnsEmptyString() {
        val help = executor.help("unknownMethod")
        assertEquals("", help)
    }
}
