/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.platon.pulsar.tools

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.persist.metadata.ParseStatusCodes
import ai.platon.pulsar.persist.model.GoraWebPage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

/**
 * Unit tests for [TikaParser], the only main class of this module.
 *
 * The quality-gate profile's JaCoCo floor measures each module's own bundle, and this class had
 * no test of its own: the module's single legacy `@Test` exercises the HTML parser that lives in
 * `browser4-skeleton`, so `TikaParser` sat at 0.00 instruction coverage and failed the floor.
 * These tests drive the real parser (Apache Tika is on this module's compile classpath) with an
 * in-memory page, so no browser, network or datastore is involved.
 */
@Tag("Unit")
@Tag("Fast")
class TikaParserTest {

    private val conf = ImmutableConfig()

    @Test
    @DisplayName("parse() reports SUCCESS for an HTML page")
    fun parseReportsSuccessForHtmlPage() {
        val result = TikaParser(conf = conf).parse(htmlPage())

        assertEquals(ParseStatusCodes.SUCCESS.toInt(), result.majorCode.toInt())
    }

    @Test
    @DisplayName("parse() copies the detected Tika metadata onto the page")
    fun parseCopiesTikaMetadataOntoPage() {
        val page = htmlPage()

        TikaParser(conf = conf).parse(page)

        // AutoDetectParser always records the type it detected, which is exactly the metadata the
        // copy loop in TikaParser consumes; an empty map here means the loop never ran.
        assertNotNull(
            page.metadata["Content-Type"],
            "the detected content type should have been copied onto the page metadata"
        )
    }

    private fun htmlPage() = GoraWebPage.newWebPage(EXAMPLE_URL, conf.toVolatileConfig()).apply {
        setByteArrayContent(HTML.toByteArray(StandardCharsets.UTF_8))
        contentType = "text/html"
    }

    companion object {
        private const val EXAMPLE_URL = "https://example.com/tika-parser-test"

        private const val HTML = """<!DOCTYPE html>
<html>
<head>
  <title>Tika parser fixture</title>
  <meta name="keywords" content="tika, parser">
</head>
<body>
  <p>A short paragraph Apache Tika should be able to read.</p>
</body>
</html>"""
    }
}
