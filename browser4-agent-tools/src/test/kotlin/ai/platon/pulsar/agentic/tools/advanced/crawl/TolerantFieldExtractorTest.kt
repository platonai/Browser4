/**
 * Copyright (c) Vincent Zhang, ivincent.zhang@gmail.com, Platon.AI.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.platon.pulsar.agentic.tools.advanced.crawl

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TolerantFieldExtractorTest {

    @Test
    fun `extract keeps all fields when one value is a json array`() {
        // Regression: FlatJSONExtractor deserializes Map<String, String>, so a
        // single array value made Jackson reject the whole object and the
        // entire extraction was silently lost as {}.
        val content = """
            Here are the details:
            ```json
            {
              "full name": "Guido van Rossum",
              "nationality": "Dutch",
              "notable awards": ["CWI Fellow", "NLUUG Award", "Python Software Foundation Fellow"],
              "known for": ["Python", "BDFL"]
            }
            ```
            Hope that helps.
        """.trimIndent()

        val fields = TolerantFieldExtractor.extract(content)

        assertEquals("Guido van Rossum", fields["full name"])
        assertEquals("Dutch", fields["nationality"])
        assertEquals(
            """["CWI Fellow","NLUUG Award","Python Software Foundation Fellow"]""",
            fields["notable awards"]
        )
        // The array field must not take the whole result down with it.
        assertEquals(4, fields.size)
    }

    @Test
    fun `extract stringifies nested objects as compact json`() {
        val content = """{"name":"Ada","education":{"degree":"PhD","field":"CS"}}"""
        val fields = TolerantFieldExtractor.extract(content)
        assertEquals("Ada", fields["name"])
        assertEquals("""{"degree":"PhD","field":"CS"}""", fields["education"])
    }

    @Test
    fun `extract handles null boolean and number scalars alongside arrays`() {
        // The array field forces the tolerant (loose) parse path; scalar
        // values must survive on it as plain strings, null as the empty string.
        val content = """{"a":null,"b":true,"c":42,"d":"text","e":[1,2]}"""
        val fields = TolerantFieldExtractor.extract(content)
        assertEquals("", fields["a"])
        assertEquals("true", fields["b"])
        assertEquals("42", fields["c"])
        assertEquals("text", fields["d"])
        assertEquals("[1,2]", fields["e"])
    }

    @Test
    fun `extract returns empty map when no json object is present`() {
        assertTrue(TolerantFieldExtractor.extract("I could not find the page.").isEmpty())
        assertTrue(TolerantFieldExtractor.extract("").isEmpty())
    }

    @Test
    fun `extract ignores braces inside strings when locating the object`() {
        val content = """note: "a } brace" then {"k": "v { w } x"}"""
        val fields = TolerantFieldExtractor.extract(content)
        assertEquals("v { w } x", fields["k"])
    }

    @Test
    fun `extractFirstJsonObject handles fenced blocks`() {
        val content = "prefix\n```json\n{\"a\": 1}\n```\nsuffix"
        assertEquals("""{"a": 1}""", TolerantFieldExtractor.extractFirstJsonObject(content))
    }

    @Test
    fun `extractFirstJsonObject returns null without an object`() {
        assertNull(TolerantFieldExtractor.extractFirstJsonObject("just text [1,2,3]"))
    }
}
