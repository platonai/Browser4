package ai.platon.pulsar.agentic.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Child-process output decoding.
 *
 * Origin: the 2026-10-28 supervision run produced 18 mojibake `ParserError` results
 * (`Դ�ı����޷�ʶ��`) because a Windows console program writes its own error text in
 * the OEM codepage while the reader decoded everything as UTF-8. UTF-8 output must
 * keep working unchanged; only undecodable lines fall back to the platform charset.
 */
@DisplayName("CLI output decoding")
class CliOutputDecodingTest {

    private val manager = CliProcessManager()

    @Test
    @DisplayName("UTF-8 output decodes unchanged")
    fun utf8StaysUtf8() {
        val text = "Crawl task submitted: 微波炉 — 20 pages"

        assertEquals(text, manager.decodeLine(text.toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    @DisplayName("a GBK-encoded error line falls back to the platform charset")
    fun gbkLineFallsBack() {
        // Exactly the kind of message pwsh writes in the OEM codepage on a zh-CN host.
        val message = "源文本无法识别"
        val gbk = Charset.forName("GBK")

        assertEquals(message, manager.decodeLine(message.toByteArray(gbk), gbk))
    }

    @Test
    @DisplayName("CRLF is normalised and empty input stays empty")
    fun crlfAndEmpty() {
        assertEquals("line", manager.decodeLine("line\r".toByteArray(StandardCharsets.UTF_8)))
        assertEquals("", manager.decodeLine(ByteArray(0)))
    }
}
