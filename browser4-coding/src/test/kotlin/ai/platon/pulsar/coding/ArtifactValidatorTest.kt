package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ArtifactValidatorTest {

    @TempDir
    lateinit var tempDir: Path

    // --- Plugin validation ---

    @Test
    @DisplayName("validatePlugin returns error for non-existent directory")
    fun validatePluginNonExistent() {
        val result = ArtifactValidator.validatePlugin("/nonexistent/path")
        assertFalse(result.valid)
        assertTrue(result.issues.any { it.severity == Severity.ERROR })
    }

    @Test
    @DisplayName("validatePlugin detects missing pom.xml")
    fun validatePluginMissingPom() {
        val pluginDir = tempDir.resolve("test-plugin").toFile()
        pluginDir.mkdirs()

        val result = ArtifactValidator.validatePlugin(pluginDir.absolutePath)
        assertFalse(result.valid)
        assertTrue(result.issues.any { it.message.contains("pom.xml") })
    }

    @Test
    @DisplayName("validatePlugin passes for well-formed plugin")
    fun validatePluginWellFormed() {
        val pluginDir = tempDir.resolve("test-plugin").toFile()
        pluginDir.mkdirs()

        // Create pom.xml
        pluginDir.resolve("pom.xml").writeText("""
            <?xml version="1.0"?>
            <project>
                <parent>
                    <groupId>ai.platon.pulsar</groupId>
                    <artifactId>browser4-pdk</artifactId>
                </parent>
                <artifactId>test-plugin</artifactId>
                <packaging>jar</packaging>
            </project>
        """.trimIndent())

        // Create plugin.json
        val metaDir = pluginDir.resolve("src/main/resources/META-INF")
        metaDir.mkdirs()
        metaDir.resolve("browser4-plugin.json").writeText("""
            {"id": "test-plugin", "name": "TestPlugin", "version": "1.0.0", "domain": "test"}
        """.trimIndent())

        // Create AutoConfiguration.imports
        val springDir = pluginDir.resolve("src/main/resources/META-INF/spring")
        springDir.mkdirs()
        springDir.resolve("org.springframework.boot.autoconfigure.AutoConfiguration.imports")
            .writeText("ai.platon.pulsar.test.config.TestPluginAutoConfiguration")

        // Create Kotlin file
        val kotlinDir = pluginDir.resolve("src/main/kotlin/ai/platon/pulsar/test/config")
        kotlinDir.mkdirs()
        kotlinDir.resolve("TestPluginAutoConfiguration.kt").writeText("""
            package ai.platon.pulsar.test.config

            class TestPluginAutoConfiguration
        """.trimIndent())

        // Create ToolExecutor
        val toolDir = pluginDir.resolve("src/main/kotlin/ai/platon/pulsar/test/tools")
        toolDir.mkdirs()
        toolDir.resolve("TestPluginToolExecutor.kt").writeText("""
            package ai.platon.pulsar.test.tools

            import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor

            class TestPluginToolExecutor : AbstractToolExecutor() {
                override val domain = "test"
                override val receiverClass = Unit::class

                init {
                    toolSpec["doTest"] = ToolSpec(domain = "test", method = "doTest")
                }

                override suspend fun callFunctionOn(
                    domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
                ): Any? = "ok"
            }
        """.trimIndent())

        val result = ArtifactValidator.validatePlugin(pluginDir.absolutePath)
        assertTrue(result.issues.none { it.severity == Severity.ERROR },
            "Expected no errors but got: ${result.issues.filter { it.severity == Severity.ERROR }}")
    }

    @Test
    @DisplayName("validatePlugin warns when receiverClass is not WebDriver")
    fun validatePluginReceiverClassWarning() {
        val pluginDir = tempDir.resolve("rc-plugin").toFile()
        pluginDir.mkdirs()
        pluginDir.resolve("pom.xml").writeText(
            "<project><parent><artifactId>browser4-pdk</artifactId></parent><artifactId>rc-plugin</artifactId></project>")
        val metaDir = pluginDir.resolve("src/main/resources/META-INF")
        metaDir.mkdirs()
        metaDir.resolve("browser4-plugin.json").writeText(
            """{"name": "rc-plugin", "version": "1.0.0", "autoConfigurationClasses": ["x.Y"]}""")
        val springDir = pluginDir.resolve("src/main/resources/META-INF/spring")
        springDir.mkdirs()
        springDir.resolve("org.springframework.boot.autoconfigure.AutoConfiguration.imports")
            .writeText("ai.platon.pulsar.test.config.TestPluginAutoConfiguration")
        val kotlinDir = pluginDir.resolve("src/main/kotlin/ai/platon/pulsar/test/config")
        kotlinDir.mkdirs()
        kotlinDir.resolve("TestPluginAutoConfiguration.kt").writeText("""
            package ai.platon.pulsar.test.config
            import ai.platon.pulsar.agentic.tools.ToolMount
            class TestPluginAutoConfiguration : ToolMount {
                override fun getToolExecutors() = emptyList<ai.platon.pulsar.agentic.tools.builtin.ToolExecutor>()
            }
        """.trimIndent())
        val toolDir = pluginDir.resolve("src/main/kotlin/ai/platon/pulsar/test/tools")
        toolDir.mkdirs()
        toolDir.resolve("TestPluginToolExecutor.kt").writeText("""
            package ai.platon.pulsar.test.tools
            import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
            class TestPluginToolExecutor : AbstractToolExecutor() {
                override val domain = "test"
                override val receiverClass = Unit::class
                init { toolSpec["doTest"] = ToolSpec(domain = "test", method = "doTest") }
                override suspend fun callFunctionOn(
                    domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
                ): Any? = "ok"
            }
        """.trimIndent())

        val result = ArtifactValidator.validatePlugin(pluginDir.absolutePath)
        assertTrue(result.issues.any { it.message.contains("receiverClass") && it.message.contains("WebDriver") },
            "Expected receiverClass warning, got: ${result.issues}")
    }

    @Test
    @DisplayName("validatePlugin warns when manifest name differs from artifactId")
    fun validatePluginManifestNameMismatch() {
        val pluginDir = tempDir.resolve("name-mismatch").toFile()
        pluginDir.mkdirs()
        pluginDir.resolve("pom.xml").writeText(
            "<project><parent><artifactId>browser4-pdk</artifactId></parent><artifactId>real-artifact</artifactId></project>")
        val metaDir = pluginDir.resolve("src/main/resources/META-INF")
        metaDir.mkdirs()
        metaDir.resolve("browser4-plugin.json").writeText(
            """{"name": "different-name", "version": "1.0.0"}""")

        val result = ArtifactValidator.validatePlugin(pluginDir.absolutePath)
        assertTrue(result.issues.any { it.message.contains("does not match pom") },
            "Expected name/artifactId mismatch warning, got: ${result.issues}")
    }

    @Test
    @DisplayName("validatePlugin warns when Service loads a missing JS resource")
    fun validatePluginMissingJsResource() {
        val pluginDir = tempDir.resolve("js-missing").toFile()
        pluginDir.mkdirs()
        pluginDir.resolve("pom.xml").writeText(
            "<project><parent><artifactId>browser4-pdk</artifactId></parent><artifactId>js-missing</artifactId></project>")
        val kotlinDir = pluginDir.resolve("src/main/kotlin/ai/platon/pulsar/test/service")
        kotlinDir.mkdirs()
        kotlinDir.resolve("TestService.kt").writeText("""
            package ai.platon.pulsar.test.service
            class TestService {
                private val script: String by lazy { loadResource("/test/missing.js") }
                private fun loadResource(path: String): String = path
            }
        """.trimIndent())

        val result = ArtifactValidator.validatePlugin(pluginDir.absolutePath)
        assertTrue(result.issues.any { it.message.contains("missing.js") && it.message.contains("loadResource") },
            "Expected missing JS resource warning, got: ${result.issues}")
    }

    // --- Plugin JSON validation ---

    @Test
    @DisplayName("validatePluginJson passes for valid JSON")
    fun validatePluginJsonValid() {
        val json = """{"id": "test", "name": "Test", "version": "1.0.0", "domain": "test"}"""
        val issues = ArtifactValidator.validatePluginJson(json)
        assertTrue(issues.none { it.severity == Severity.ERROR })
    }

    @Test
    @DisplayName("validatePluginJson detects missing required fields")
    fun validatePluginJsonMissingFields() {
        val json = """{"id": "test"}"""
        val issues = ArtifactValidator.validatePluginJson(json)
        assertTrue(issues.any { it.message.contains("name") && it.severity == Severity.ERROR })
        assertTrue(issues.any { it.message.contains("version") && it.severity == Severity.ERROR })
    }

    @Test
    @DisplayName("validatePluginJson detects invalid JSON")
    fun validatePluginJsonInvalid() {
        val json = "not json at all"
        val issues = ArtifactValidator.validatePluginJson(json)
        assertTrue(issues.any { it.severity == Severity.ERROR })
    }

    // --- POM XML validation ---

    @Test
    @DisplayName("validatePomXml passes for valid pom with browser4-pdk parent")
    fun validatePomXmlValid() {
        val pom = """
            <project>
                <parent>
                    <artifactId>browser4-pdk</artifactId>
                </parent>
                <artifactId>test-plugin</artifactId>
                <packaging>jar</packaging>
            </project>
        """.trimIndent()
        val issues = ArtifactValidator.validatePomXml(pom)
        assertTrue(issues.none { it.severity == Severity.ERROR })
    }

    @Test
    @DisplayName("validatePomXml warns about missing parent")
    fun validatePomXmlMissingParent() {
        val pom = """
            <project>
                <artifactId>test-plugin</artifactId>
            </project>
        """.trimIndent()
        val issues = ArtifactValidator.validatePomXml(pom)
        assertTrue(issues.any { it.severity == Severity.WARNING && it.message.contains("parent") })
    }

    @Test
    @DisplayName("validatePomXml errors on missing artifactId")
    fun validatePomXmlMissingArtifactId() {
        val pom = "<project><parent><artifactId>browser4-pdk</artifactId></parent></project>"
        val issues = ArtifactValidator.validatePomXml(pom)
        assertTrue(issues.any { it.severity == Severity.ERROR && it.message.contains("artifactId") })
    }

    // --- Kotlin ToolExecutor validation ---

    @Test
    @DisplayName("validateKotlinToolExecutor passes for well-formed executor")
    fun validateKotlinToolExecutorValid() {
        val content = """
            package ai.platon.pulsar.test.tools

            class TestToolExecutor : AbstractToolExecutor() {
                override val domain = "test"
                override val receiverClass = Unit::class

                init {
                    toolSpec["doTest"] = ToolSpec(domain = "test", method = "doTest")
                }

                override suspend fun callFunctionOn(
                    domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
                ): Any? = "ok"
            }
        """.trimIndent()
        val issues = ArtifactValidator.validateKotlinToolExecutor(content, "TestToolExecutor.kt")
        assertTrue(issues.none { it.severity == Severity.ERROR },
            "Expected no errors but got: ${issues.filter { it.severity == Severity.ERROR }}")
    }

    @Test
    @DisplayName("validateKotlinToolExecutor detects missing callFunctionOn")
    fun validateKotlinToolExecutorMissingDispatch() {
        val content = """
            package test
            class TestToolExecutor : AbstractToolExecutor() {
                override val domain = "test"
            }
        """.trimIndent()
        val issues = ArtifactValidator.validateKotlinToolExecutor(content, "Test.kt")
        assertTrue(issues.any { it.severity == Severity.ERROR && it.message.contains("callFunctionOn") })
    }

    @Test
    @DisplayName("validateKotlinToolExecutor detects unbalanced braces")
    fun validateKotlinToolExecutorUnbalancedBraces() {
        val content = """
            package test
            class Test {
                fun foo() {
                    // missing closing brace
            }
        """.trimIndent()
        val issues = ArtifactValidator.validateKotlinToolExecutor(content, "Test.kt")
        assertTrue(issues.any { it.severity == Severity.ERROR && it.message.contains("Unbalanced") })
    }

    @Test
    @DisplayName("validateKotlinToolExecutor ignores delimiters in strings, comments and raw strings")
    fun validateKotlinToolExecutorIgnoresNonStructuralBraces() {
        // A ToolExecutor is mostly prompt/JSON literals; the old `count { it == '{' }` called this
        // file unbalanced even though it compiles. Built line by line: the injected raw-string
        // lines have no indent, which would defeat `trimIndent()` on a block literal.
        val content = listOf(
            "package test",
            "",
            "class TestToolExecutor : AbstractToolExecutor() {",
            "    override val domain = \"test\"",
            "    // prose with a lone brace: {",
            "    /* block { comment } */",
            "    private val json = \"{\\\"a\\\": 1}\"",
            "    private val doc = \"\"\"",
            "    {\"multi\": \"line\"}",
            "    \"\"\"",
            "    override fun callFunctionOn() {",
            "        toolSpec[\"x\"] = spec",
            "    }",
            "}",
        ).joinToString("\n")

        val issues = ArtifactValidator.validateKotlinToolExecutor(content, "Test.kt")
        assertTrue(
            issues.none { it.severity == Severity.ERROR },
            "a delimiter inside a literal is not code: ${issues.filter { it.severity == Severity.ERROR }}",
        )
    }

    @Test
    @DisplayName("validateKotlinToolExecutor understands quotes inside a template expression")
    fun validateKotlinToolExecutorHandlesQuotesInInterpolation() {
        // `${map["key"]}` puts a quoted string inside the interpolation. Ending the outer literal
        // at the first `"` would leave a stray `]` and report a delimiter that is not there.
        val content = listOf(
            "package test",
            "class TestToolExecutor : AbstractToolExecutor() {",
            "    fun f(map: Map<String, String>) {",
            "        val s = \"\${map[\"key\"]}\"",
            "    }",
            "    override fun callFunctionOn() {}",
            "}",
        ).joinToString("\n")

        val issues = ArtifactValidator.validateKotlinToolExecutor(content, "Test.kt")
        assertTrue(
            issues.none { it.severity == Severity.ERROR },
            "the interpolation is code, not a broken literal: ${issues.filter { it.severity == Severity.ERROR }}",
        )
    }

    // --- Skill validation ---

    @Test
    @DisplayName("validateSkill passes for well-formed SKILL.md")
    fun validateSkillValid() {
        val content = """
            ---
            name: my-skill
            description: "A test skill"
            allowed-tools: coding.read coding.write
            ---

            # My Skill

            ## When to Use
            When testing.
        """.trimIndent()
        val result = ArtifactValidator.validateSkill(content, "skills/my-skill/SKILL.md")
        assertTrue(result.issues.none { it.severity == Severity.ERROR },
            "Expected no errors but got: ${result.issues.filter { it.severity == Severity.ERROR }}")
    }

    @Test
    @DisplayName("validateSkill errors on missing frontmatter")
    fun validateSkillMissingFrontmatter() {
        val content = "# My Skill\n\nNo frontmatter here."
        val result = ArtifactValidator.validateSkill(content)
        assertTrue(result.issues.any { it.severity == Severity.ERROR && it.message.contains("frontmatter") })
    }

    @Test
    @DisplayName("validateSkill errors on missing name field")
    fun validateSkillMissingName() {
        val content = """
            ---
            description: "Test"
            ---

            # Test
        """.trimIndent()
        val result = ArtifactValidator.validateSkill(content)
        assertTrue(result.issues.any { it.severity == Severity.ERROR && it.message.contains("name") })
    }

    @Test
    @DisplayName("validateSkill errors on missing description (loader requirement)")
    fun validateSkillMissingDescription() {
        val content = """
            ---
            name: test
            ---

            # Test
        """.trimIndent()
        val result = ArtifactValidator.validateSkill(content)
        assertTrue(result.issues.any { it.severity == Severity.ERROR && it.message.contains("description") })
    }

    @Test
    @DisplayName("validateSkill errors when name does not match directory")
    fun validateSkillNameMismatchDirectory() {
        val content = """
            ---
            name: other-name
            description: "Test"
            ---

            # Test
        """.trimIndent()
        val result = ArtifactValidator.validateSkill(content, "skills/my-skill/SKILL.md")
        assertTrue(result.issues.any { it.severity == Severity.ERROR && it.message.contains("directory") })
    }

    @Test
    @DisplayName("validateSkill errors on description over 1024 chars")
    fun validateSkillDescriptionTooLong() {
        val content = """
            ---
            name: test
            description: "${"x".repeat(2000)}"
            ---

            # Test
        """.trimIndent()
        val result = ArtifactValidator.validateSkill(content)
        assertTrue(result.issues.any { it.severity == Severity.ERROR && it.message.contains("1024") })
    }

    // --- Tool reference cross-checking ---

    private val knownTools = mapOf(
        "tab" to setOf("eval", "evaluateValue", "click", "fill", "navigate", "consoleMessages"),
        "coding" to setOf("read", "write", "replace", "shell", "scaffold", "validate", "grep", "glob"),
        "cli" to setOf("run", "version", "help"),
        "seo" to setOf("extractMeta", "checkIssues"),
        "system" to setOf("help"),
    )

    @Test
    @DisplayName("validateToolReferences passes when all refs are known")
    fun toolRefsAllKnown() {
        val content = """
            Use tab.eval(expression) to read the page, then coding.write(path, content)
            to save it, and finally seo.extractMeta() to audit metadata.
        """.trimIndent()
        val issues = ArtifactValidator.validateToolReferences(content, knownTools, "SKILL.md")
        assertTrue(issues.isEmpty(), "Expected no issues but got: $issues")
    }

    @Test
    @DisplayName("validateToolReferences errors on unknown method in known domain")
    fun toolRefsUnknownMethod() {
        val content = "Call coding.replac(path, old, new) to fix the code."
        val issues = ArtifactValidator.validateToolReferences(content, knownTools, "SKILL.md")
        assertTrue(issues.any { it.severity == Severity.ERROR && it.message.contains("coding.replac") })
    }

    @Test
    @DisplayName("validateToolReferences warns on unknown domain")
    fun toolRefsUnknownDomain() {
        val content = "Call seo.doesNotExist() then ghost.extractMeta() to finish."
        val issues = ArtifactValidator.validateToolReferences(content, knownTools, "SKILL.md")
        assertTrue(issues.any { it.severity == Severity.WARNING && it.message.contains("ghost") },
            "Expected a warning about unknown domain 'ghost', got: $issues")
    }

    @Test
    @DisplayName("validateToolReferences ignores language/library APIs")
    fun toolRefsIgnoresNoise() {
        val content = """
            Use document.querySelector(selector) to find elements, console.log(msg) to debug,
            JSON.parse(text), Math.max(a, b), java.util.List.of(x), os.path.join(a, b),
            and com.fasterxml.jackson.databind.ObjectMapper() to map data.
        """.trimIndent()
        val issues = ArtifactValidator.validateToolReferences(content, knownTools, "SKILL.md")
        assertTrue(issues.isEmpty(), "Expected no noise issues but got: $issues")
    }

    @Test
    @DisplayName("validateToolReferences dedupes repeated calls")
    fun toolRefsDedupe() {
        val content = "Call tab.eval(x) then tab.eval(y) — tab.eval(z) is the same tool."
        val issues = ArtifactValidator.validateToolReferences(content, knownTools, "SKILL.md")
        assertTrue(issues.isEmpty())
    }

    @Test
    @DisplayName("validateToolReferences ignores known domain but missing paren")
    fun toolRefsIgnoresNonCalls() {
        // Prose mention without a call (no paren) is not a tool invocation
        val content = "See the tab.eval docs and the coding domain for details."
        val issues = ArtifactValidator.validateToolReferences(content, knownTools, "SKILL.md")
        assertTrue(issues.isEmpty(), "Expected no issues for prose mentions, got: $issues")
    }

    @Test
    @DisplayName("validateToolReferences tolerates whitespace around the dot")
    fun toolRefsWhitespaceAroundDot() {
        val content = "Call coding . read(path) to read the file."
        val issues = ArtifactValidator.validateToolReferences(content, knownTools, "SKILL.md")
        assertTrue(issues.isEmpty(), "Expected no issues, got: $issues")
    }

    // --- JS validation ---

    @Test
    @DisplayName("validateJs passes for well-formed IIFE")
    fun validateJsValid() {
        val content = """
            (function() {
                'use strict';
                var result = { data: [] };
                return JSON.stringify(result);
            })();
        """.trimIndent()
        val result = ArtifactValidator.validateJs(content, "test.js")
        assertTrue(result.issues.none { it.severity == Severity.ERROR },
            "Expected no errors but got: ${result.issues.filter { it.severity == Severity.ERROR }}")
    }

    @Test
    @DisplayName("validateJs detects unbalanced brackets")
    fun validateJsUnbalancedBrackets() {
        val content = "(function() { return 'ok'; );"
        val result = ArtifactValidator.validateJs(content, "test.js")
        assertTrue(result.issues.any { it.severity == Severity.ERROR && it.message.contains("Unbalanced") })
    }

    @Test
    @DisplayName("validateJs ignores delimiters in prose comments")
    fun validateJsIgnoresCommentProse() {
        // 2026-11-20 run: `coding_validate(type=js)` counted the `1)` / `2)` list markers in a
        // comment and reported unbalanced parentheses, so the agent rewrote its comments to pass.
        val content = """
            (function() {
                'use strict';
                // The account page exposes two endpoints:
                //   1) /ajax/statuses/mymblog
                //   2) /ajax/statuses/longtext
                var where = location.href;
                return { where: where };
            })();
        """.trimIndent()

        val result = ArtifactValidator.validateJs(content, "test.js")
        assertTrue(
            result.issues.none { it.severity == Severity.ERROR },
            "a bracket in a comment is not syntax: ${result.issues.filter { it.severity == Severity.ERROR }}",
        )
    }

    @Test
    @DisplayName("validateJs ignores delimiters in strings, templates and regexes")
    fun validateJsIgnoresLiterals() {
        val content = """
            (function() {
                'use strict';
                var s = "hello (world";
                var t = `value: ${'$'}{s.length})`;
                var re = /recaptcha\.execute\((.+)/;
                var re2 = /\/u\/(\d+)/;
                var q = 'a[href*="/u/"]';
                var ratio = s.length / 2;
                return { s: s, t: t, re: String(re), re2: String(re2), q: q, ratio: ratio };
            })();
        """.trimIndent()

        val result = ArtifactValidator.validateJs(content, "test.js")
        assertTrue(
            result.issues.none { it.severity == Severity.ERROR },
            "delimiters inside literals are not syntax: ${result.issues.filter { it.severity == Severity.ERROR }}",
        )
    }

    @Test
    @DisplayName("validateJs still catches a closer hidden in a comment")
    fun validateJsCatchesMissingCloserHiddenByComment() {
        // The old raw count was balanced here (3 open / 3 close) because the comment supplied the
        // missing `)`: the broken script passed and only failed later in `tab.eval`.
        val content = """
            (function() {
                'use strict';
                var x = 1;
                // legacy note: )
            }();
        """.trimIndent()

        val result = ArtifactValidator.validateJs(content, "test.js")
        val error = result.issues.firstOrNull { it.severity == Severity.ERROR }
        assertNotNull(error, "a missing closer must not be excused by a comment: ${result.issues}")
        assertTrue(error!!.message.contains("Unbalanced"), error.message)
        assertTrue(error.message.contains("line "), "the message must locate the problem: ${error.message}")
    }

    @Test
    @DisplayName("validateJs reports a mismatched pair instead of only counting it")
    fun validateJsReportsMismatchedPairs() {
        // `)(` has as many openers as closers, so the old count called it balanced.
        val content = "(function() { return 1; ))(;"
        val result = ArtifactValidator.validateJs(content, "test.js")
        assertTrue(
            result.issues.any { it.severity == Severity.ERROR && it.message.contains("Unbalanced") },
            "a closer that does not match the open delimiter is an error: ${result.issues}",
        )
    }

    @Test
    @DisplayName("validateJs warns about missing return")
    fun validateJsMissingReturn() {
        val content = "(function() { 'use strict'; var x = 1; })();"
        val result = ArtifactValidator.validateJs(content, "test.js")
        assertTrue(result.issues.any { it.severity == Severity.WARNING && it.message.contains("return") })
    }

    @Test
    @DisplayName("validateJs warns about document.write")
    fun validateJsDocumentWrite() {
        val content = "(function() { 'use strict'; document.write('test'); return 'ok'; })();"
        val result = ArtifactValidator.validateJs(content, "test.js")
        assertTrue(result.issues.any { it.severity == Severity.WARNING && it.message.contains("document.write") })
    }

    @Test
    @DisplayName("validateJs does not read words out of comments and strings")
    fun validateJsWordHeuristicsSeeCodeOnly() {
        // `// no return value here` used to satisfy the return check, `// eval( is dangerous` used
        // to produce an eval warning, and a quoted `document.write(html)` used to warn too.
        val content = """
            (function() {
                'use strict';
                // no return value here
                // eval( is dangerous
                var example = "document.write(html)";
                var x = 1;
            })();
        """.trimIndent()

        val result = ArtifactValidator.validateJs(content, "test.js")
        assertTrue(
            result.issues.any { it.severity == Severity.WARNING && it.message.contains("return") },
            "a comment must not satisfy the return check: ${result.issues}",
        )
        assertTrue(
            result.issues.none { it.severity == Severity.WARNING && it.message.contains("eval(") },
            "eval( in a comment is not a call: ${result.issues}",
        )
        assertTrue(
            result.issues.none { it.severity == Severity.WARNING && it.message.contains("document.write") },
            "document.write( in a string is not a call: ${result.issues}",
        )
    }

    @Test
    @DisplayName("validateJs sees code inside a template expression, not the template text")
    fun validateJsLooksInsideTemplateExpressions() {
        // `eval(` in `${…}` is a real call; the same word in the template text around it is data.
        val content = """
            (function() {
                'use strict';
                var v = 1;
                var s = `eval( in text`;
                var t = `value=${'$'}{eval("v")}`;
                return [s, t];
            })();
        """.trimIndent()

        val result = ArtifactValidator.validateJs(content, "test.js")
        val evalWarnings = result.issues.filter {
            it.severity == Severity.WARNING && it.message.contains("eval(")
        }
        assertEquals(1, evalWarnings.size, "exactly the interpolation is code: ${result.issues}")
    }

    // --- Script validation ---

    @Test
    @DisplayName("validateScript PS1 checks for param and ErrorActionPreference")
    fun validateScriptPs1() {
        val content = """
            param([string]${'$'}Param1)
            ${'$'}ErrorActionPreference = "Stop"
            Write-Host "Hello"
        """.trimIndent()
        val result = ArtifactValidator.validateScript(content, "build.ps1")
        assertTrue(result.issues.none { it.severity == Severity.ERROR })
    }

    @Test
    @DisplayName("validateScript PS1 warns about missing param block")
    fun validateScriptPs1MissingParam() {
        val content = "Write-Host 'Hello'"
        val result = ArtifactValidator.validateScript(content, "build.ps1")
        assertTrue(result.issues.any { it.severity == Severity.INFO && it.message.contains("param") })
    }

    @Test
    @DisplayName("validateScript PS1 ignores braces in comments, strings and here-strings")
    fun validateScriptPs1IgnoresNonStructuralBraces() {
        // The raw count this replaces reported `# usage: { -Path }` and `'{"a":1}'` as an
        // unbalanced script, so the agent rewrote working scripts to satisfy the checker.
        val content = """
            param([string]${'$'}Path)
            ${'$'}ErrorActionPreference = "Stop"
            # usage: Get-Thing { -Path <dir> }
            <# block comment with { and } #>
            ${'$'}json = '{"a": 1}'
            ${'$'}msg = "a closing brace } inside a string"
            ${'$'}here = @"
            literal { brace in a here-string
            "@
            Write-Host ${'$'}msg ${'$'}json ${'$'}here
        """.trimIndent()

        val result = ArtifactValidator.validateScript(content, "build.ps1")
        assertTrue(
            result.issues.none { it.severity == Severity.ERROR },
            "non-structural braces must not fail the script: ${result.issues.filter { it.severity == Severity.ERROR }}",
        )
    }

    @Test
    @DisplayName("validateScript PS1 still catches a brace that is never closed")
    fun validateScriptPs1DetectsUnbalancedBraces() {
        val content = """
            param([string]${'$'}Path)
            ${'$'}ErrorActionPreference = "Stop"
            if (${'$'}Path) {
                Write-Host ${'$'}Path
        """.trimIndent()

        val result = ArtifactValidator.validateScript(content, "build.ps1")
        val error = result.issues.firstOrNull { it.severity == Severity.ERROR }
        assertNotNull(error, "a missing '}' has to be reported: ${result.issues}")
        assertTrue(error!!.message.contains("Unbalanced"), error.message)
        assertTrue(error.message.contains("line "), "the message must locate the problem: ${error.message}")
    }

    @Test
    @DisplayName("validateScript warns when a ps1 carries non-ASCII text")
    fun validateScriptPs1WarnsAboutNonAscii() {
        // Windows PowerShell 5.1 reads a BOM-less script as ANSI, so a Chinese literal is
        // corrupted before the script runs (measured: 中文 under pwsh, 涓枃 under powershell).
        val content = """
            param([string]${'$'}Path)
            ${'$'}ErrorActionPreference = "Stop"
            Write-Host "处理 ${'$'}Path"
        """.trimIndent()

        val result = ArtifactValidator.validateScript(content, "build.ps1")
        assertTrue(
            result.issues.any { it.severity == Severity.WARNING && it.message.contains("Non-ASCII") },
            "the encoding footgun must be named: ${result.issues}",
        )

        val ascii = "param([string]\$Path)\n\$ErrorActionPreference = \"Stop\"\nWrite-Host \$Path"
        assertTrue(
            ArtifactValidator.validateScript(ascii, "build.ps1")
                .issues.none { it.message.contains("Non-ASCII") },
            "an ASCII script is fine",
        )
    }

    @Test
    @DisplayName("validateScript Bash checks for shebang and set -e")
    fun validateScriptBash() {
        val content = """
            #!/usr/bin/env bash
            set -euo pipefail
            echo "Hello"
        """.trimIndent()
        val result = ArtifactValidator.validateScript(content, "build.sh")
        assertTrue(result.issues.none { it.severity == Severity.ERROR })
    }

    @Test
    @DisplayName("validateScript Bash warns about missing shebang")
    fun validateScriptBashMissingShebang() {
        val content = "echo 'Hello'"
        val result = ArtifactValidator.validateScript(content, "build.sh")
        assertTrue(result.issues.any { it.severity == Severity.WARNING && it.message.contains("shebang") })
    }

    @Test
    @DisplayName("validateScript warns about unknown file type")
    fun validateScriptUnknownType() {
        val content = "echo hello"
        val result = ArtifactValidator.validateScript(content, "script.txt")
        assertTrue(result.issues.any { it.severity == Severity.WARNING && it.message.contains("Cannot determine") })
    }

    // --- ValidationResult.format ---

    @Test
    @DisplayName("ValidationResult.format shows pass message for no issues")
    fun validationResultFormatEmpty() {
        val result = ValidationResult.valid()
        assertEquals("✓ All checks passed.", result.format())
    }

    @Test
    @DisplayName("ValidationResult.format shows issues with icons")
    fun validationResultFormatWithIssues() {
        val result = ValidationResult.of(listOf(
            ValidationIssue(Severity.ERROR, "Missing pom.xml", "pom.xml"),
            ValidationIssue(Severity.WARNING, "No param block", "build.ps1"),
            ValidationIssue(Severity.INFO, "Consider use strict", "test.js")
        ))
        val formatted = result.format()
        assertTrue(formatted.contains("✗"))
        assertTrue(formatted.contains("⚠"))
        assertTrue(formatted.contains("ℹ"))
        assertTrue(formatted.contains("Missing pom.xml"))
        assertTrue(formatted.contains("No param block"))
        assertTrue(formatted.contains("Consider use strict"))
    }

    @Test
    @DisplayName("ValidationResult.of marks invalid when errors exist")
    fun validationResultInvalidOnErrors() {
        val result = ValidationResult.of(listOf(
            ValidationIssue(Severity.ERROR, "error"),
            ValidationIssue(Severity.WARNING, "warning")
        ))
        assertFalse(result.valid)
    }

    @Test
    @DisplayName("ValidationResult.of marks valid when only warnings")
    fun validationResultValidWithWarnings() {
        val result = ValidationResult.of(listOf(
            ValidationIssue(Severity.WARNING, "warning"),
            ValidationIssue(Severity.INFO, "info")
        ))
        assertTrue(result.valid)
    }
}

