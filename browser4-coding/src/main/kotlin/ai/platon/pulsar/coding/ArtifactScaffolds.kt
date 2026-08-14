package ai.platon.pulsar.coding

/**
 * Scaffold templates for Browser4's four core programming artifact types.
 *
 * Each function returns template text that an agent can use as a starting point
 * for creating: Browser4 plugins, skills, browser JS scripts, and simple shell scripts.
 *
 * Zero dependencies — pure string generation.
 */
object ArtifactScaffolds {

    /**
     * Supported artifact types.
     */
    val TYPES = listOf("plugin", "skill", "js", "script")

    /**
     * Generate scaffold by artifact type. Returns a map of file-path to content
     * (for multi-file types like plugin) or a single-entry map with key "_content".
     */
    fun scaffold(
        type: String,
        params: Map<String, String>
    ): Map<String, String> = when (type) {
        "plugin" -> pluginScaffold(
            pluginName = params["pluginName"] ?: "my-plugin",
            domain = params["domain"] ?: "my",
            basePackage = params["basePackage"] ?: "ai.platon.pulsar.my",
            toolMethod = params["toolMethod"] ?: "doAction",
            toolDescription = params["toolDescription"] ?: "Performs an action",
            pdkVersion = params["pdkVersion"] ?: "4.13.4-SNAPSHOT"
        )
        "skill" -> mapOf("_content" to skillScaffold(
            name = params["name"] ?: "my-skill",
            description = params["description"] ?: "A custom skill",
            triggers = (params["triggers"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() },
            tools = (params["tools"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        ))
        "js" -> mapOf("_content" to jsScaffold(
            name = params["name"] ?: "my-script",
            purpose = params["purpose"] ?: "extract"
        ))
        "script" -> mapOf("_content" to scriptScaffold(
            name = params["name"] ?: "build",
            scriptType = params["scriptType"] ?: "build",
            shell = params["shell"] ?: "ps1"
        ))
        else -> throw IllegalArgumentException("Unknown scaffold type: $type. Supported: $TYPES")
    }

    // ==================== Plugin ====================

    /**
     * Generate a complete Browser4 plugin scaffold.
     *
     * @param pluginName kebab-case plugin name (e.g., "browser4-seo")
     * @param domain tool domain (e.g., "seo")
     * @param basePackage Kotlin base package (e.g., "ai.platon.pulsar.seo")
     * @param toolMethod first tool method name (e.g., "extractMeta")
     * @param toolDescription human-readable description of the tool
     * @param pdkVersion the browser4-pdk parent version to use (defaults to the current project version)
     * @return map of relative file paths to file content
     */
    fun pluginScaffold(
        pluginName: String,
        domain: String,
        basePackage: String,
        toolMethod: String,
        toolDescription: String,
        pdkVersion: String = "4.13.4-SNAPSHOT"
    ): Map<String, String> {
        // Match the ecosystem naming convention: real plugins strip the "browser4-"
        // prefix for class names (browser4-seo -> SeoConfig/SeoAutoConfiguration/SeoToolExecutor).
        val className = toClassName(pluginName.removePrefix("browser4-"))
        val packagePath = basePackage.replace('.', '/')
        val configClass = "${className}Config"
        val autoConfigClass = "${className}AutoConfiguration"
        val toolExecutorClass = "${className}ToolExecutor"
        val autoConfigFqn = "$basePackage.config.$autoConfigClass"

        return linkedMapOf(
            "pom.xml" to pluginPom(pluginName, pdkVersion),
            "src/main/resources/META-INF/browser4-plugin.json" to
                pluginJson(pluginName, toolDescription, autoConfigFqn),
            "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports" to
                autoConfigFqn,
            "src/main/kotlin/$packagePath/config/$configClass.kt" to
                pluginConfig(basePackage, configClass),
            "src/main/kotlin/$packagePath/config/$autoConfigClass.kt" to
                pluginAutoConfig(basePackage, autoConfigClass, toolExecutorClass, configClass, pluginName, domain),
            "src/main/kotlin/$packagePath/tools/$toolExecutorClass.kt" to
                pluginToolExecutor(basePackage, toolExecutorClass, domain, toolMethod, toolDescription),
            "README.md" to pluginReadme(pluginName, domain, toolMethod, toolDescription)
        )
    }

    private fun pluginPom(pluginName: String, pdkVersion: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0"
                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
            <modelVersion>4.0.0</modelVersion>

            <parent>
                <groupId>ai.platon.pulsar</groupId>
                <artifactId>browser4-pdk</artifactId>
                <version>$pdkVersion</version>
                <relativePath>../../browser4-pdk/pom.xml</relativePath>
            </parent>

            <artifactId>$pluginName</artifactId>
            <packaging>jar</packaging>

            <dependencies>
                <dependency>
                    <groupId>ai.platon.pulsar</groupId>
                    <artifactId>browser4-skeleton</artifactId>
                    <scope>provided</scope>
                </dependency>
                <dependency>
                    <groupId>ai.platon.pulsar</groupId>
                    <artifactId>browser4-protocol</artifactId>
                    <scope>provided</scope>
                </dependency>
                <dependency>
                    <groupId>ai.platon.pulsar</groupId>
                    <artifactId>browser4-agentic</artifactId>
                    <scope>provided</scope>
                </dependency>
                <dependency>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-autoconfigure</artifactId>
                    <scope>provided</scope>
                </dependency>
                <dependency>
                    <groupId>org.jetbrains.kotlin</groupId>
                    <artifactId>kotlin-stdlib</artifactId>
                    <scope>provided</scope>
                </dependency>
                <dependency>
                    <groupId>org.jetbrains.kotlinx</groupId>
                    <artifactId>kotlinx-coroutines-core</artifactId>
                    <scope>provided</scope>
                </dependency>
            </dependencies>
        </project>
    """.trimIndent()

    /**
     * Generate `META-INF/browser4-plugin.json` matching the real [ai.platon.pulsar.skeleton.plugin.PluginManifest]
     * contract: name (artifact id), version, description, dependsOn, autoConfigurationClasses.
     */
    private fun pluginJson(
        pluginName: String,
        description: String,
        autoConfigFqn: String
    ): String = """
        {
          "name": "$pluginName",
          "version": "1.0.0",
          "description": "${description.replace("\"", "\\\"")}",
          "dependsOn": ["browser4-skeleton", "browser4-protocol", "browser4-agentic"],
          "autoConfigurationClasses": ["$autoConfigFqn"]
        }
    """.trimIndent()

    private fun pluginConfig(basePackage: String, configClass: String): String = """
        package $basePackage.config

        import ai.platon.pulsar.common.config.ImmutableConfig
        import ai.platon.pulsar.common.config.MutableConfig

        /**
         * Configuration for the plugin.
         *
         * Define config properties with the [MutableConfig] prefix mechanism:
         * ```kotlin
         * val myProp: String get() = conf.getWithDefault("${"$"}{prefix}.my-prop", "default")
         * ```
         */
        class $configClass(config: MutableConfig) : ImmutableConfig(config)
    """.trimIndent()

    private fun pluginAutoConfig(
        basePackage: String,
        autoConfigClass: String,
        toolExecutorClass: String,
        configClass: String,
        pluginName: String,
        domain: String
    ): String {
        val camel = toCamelCase(pluginName.removePrefix("browser4-"))
        val configBean = "${camel}Config"
        val executorBean = "${camel}ToolExecutor"

        return """
            package $basePackage.config

            import org.springframework.boot.autoconfigure.AutoConfiguration
            import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
            import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
            import org.springframework.context.ApplicationContext
            import org.springframework.context.annotation.Bean
            import org.springframework.context.annotation.Lazy
            import ai.platon.pulsar.agentic.tools.ToolMount
            import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
            import ai.platon.pulsar.common.config.MutableConfig
            import $basePackage.tools.$toolExecutorClass

            /**
             * Auto-configuration for the $pluginName plugin.
             *
             * Implements [ToolMount] so that PluginManager automatically registers
             * the plugin's tools into CustomToolRegistry (visible to the LLM agent).
             * Disable with `${domain}.enabled=false`.
             */
            @AutoConfiguration
            @ConditionalOnProperty(name = ["$domain.enabled"], havingValue = "true", matchIfMissing = true)
            @Lazy
            open class $autoConfigClass(
                private val applicationContext: ApplicationContext,
            ) : ToolMount {

                override fun getToolExecutors(): List<ToolExecutor> {
                    return try {
                        listOf(applicationContext.getBean("$executorBean") as ToolExecutor)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }

                @Bean(name = ["$configBean"])
                @ConditionalOnMissingBean(name = ["$configBean"])
                open fun ${camel}Config(config: MutableConfig) = $configClass(config)

                @Bean(name = ["$executorBean"])
                @ConditionalOnMissingBean(name = ["$executorBean"])
                open fun ${camel}ToolExecutor() = $toolExecutorClass()
            }
        """.trimIndent()
    }

    private fun pluginToolExecutor(
        basePackage: String,
        toolExecutorClass: String,
        domain: String,
        toolMethod: String,
        toolDescription: String
    ): String = """
        package $basePackage.tools

        import ai.platon.pulsar.agentic.model.ToolSpec
        import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor

        /**
         * Tool executor for the "$domain" domain.
         *
         * Register tools in [init] and dispatch them in [callFunctionOn].
         */
        class $toolExecutorClass : AbstractToolExecutor() {

            override val domain = "$domain"
            override val receiverClass = Unit::class

            init {
                toolSpec["$toolMethod"] = ToolSpec(
                    domain = domain,
                    method = "$toolMethod",
                    arguments = listOf(
                        ToolSpec.Arg("param", "String", "null")
                    ),
                    returnType = "String",
                    description = "$toolDescription"
                )
            }

            @Throws(IllegalArgumentException::class)
            override suspend fun callFunctionOn(
                domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
            ): Any? {
                require(domain == this.domain) { "Unsupported domain: ${'$'}domain" }
                return when (functionName) {
                    "$toolMethod" -> {
                        validateArgs(args, allowed = setOf("param"), required = emptySet(), functionName)
                        val param = paramString(args, "param", functionName, required = false, default = null)
                        // TODO: Implement tool logic
                        "Result from $toolMethod(param=${'$'}param)"
                    }
                    else -> throw IllegalArgumentException("Unsupported $domain method: ${'$'}functionName(${'$'}{args.keys})")
                }
            }
        }
    """.trimIndent()

    private fun pluginReadme(pluginName: String, domain: String, toolMethod: String, toolDescription: String): String = """
        # $pluginName

        $toolDescription

        ## Domain: `$domain`

        ## Tools

        | Method | Description |
        |--------|-------------|
        | `$domain.$toolMethod` | $toolDescription |

        ## Build

        ```bash
        mvn -pl browser4-plugins/$pluginName -am compile -DskipTests
        mvn -pl browser4-plugins/$pluginName package -DskipTests
        ```

        ## Deploy

        Copy the JAR to the Browser4 plugins directory, or use REST install.
        Restart Browser4 to activate.
    """.trimIndent()

    // ==================== Skill ====================

    /**
     * Generate a SKILL.md scaffold matching the real SkillDefinitionLoader
     * frontmatter contract: `name` (must equal the directory name, kebab-case),
     * `description` (1..1024 chars), optional `allowed-tools` (space-separated).
     *
     * @param name kebab-case skill name — must match the directory the SKILL.md is placed in
     * @param description 1..1024 chars, used by the loader for matching/triggering
     * @param triggers trigger phrases rendered into the body's "When to Use" section
     * @param tools tool names (e.g. "coding.read", "tab.eval") rendered into allowed-tools + body
     */
    fun skillScaffold(
        name: String,
        description: String,
        triggers: List<String>,
        tools: List<String>
    ): String {
        val triggerLines = if (triggers.isNotEmpty()) {
            triggers.joinToString("\n") { "- $it" }
        } else {
            "- When the user asks to $name"
        }
        val toolLines = if (tools.isNotEmpty()) {
            tools.joinToString("\n") { "- `$it`" }
        } else {
            "- (list tool names here)"
        }
        val allowedToolsLine = if (tools.isNotEmpty()) {
            "allowed-tools: ${tools.joinToString(" ")}"
        } else {
            null
        }

        // buildString (not trimIndent) so multi-line interpolated values like
        // triggerLines/toolLines never defeat the indentation detection.
        return buildString {
            appendLine("---")
            appendLine("name: $name")
            appendLine("description: \"$description\"")
            if (allowedToolsLine != null) appendLine(allowedToolsLine)
            appendLine("---")
            appendLine()
            appendLine("# $name")
            appendLine()
            appendLine(description)
            appendLine()
            appendLine("## When to Use")
            appendLine()
            appendLine(triggerLines)
            appendLine()
            appendLine("## Available Tools")
            appendLine()
            appendLine(toolLines)
            appendLine()
            appendLine("## Workflow")
            appendLine()
            appendLine("1. Identify the user's intent")
            appendLine("2. Call the appropriate tool")
            appendLine("3. Process and format the result")
            appendLine("4. Report findings to the user")
            appendLine()
            appendLine("## Examples")
            appendLine()
            appendLine("```")
            appendLine("User: <example user request>")
            appendLine("Agent: <example agent response>")
            appendLine("```")
        }.trimEnd()
    }

    // ==================== JS ====================

    /**
     * Generate a browser JS script scaffold.
     *
     * @param purpose one of: "extract" (DOM data extraction),
     *                  "inject" (DOM modification),
     *                  "interact" (page interaction)
     */
    fun jsScaffold(name: String, purpose: String): String = when (purpose) {
        "extract" -> jsExtractTemplate(name)
        "inject" -> jsInjectTemplate(name)
        "interact" -> jsInteractTemplate(name)
        else -> jsExtractTemplate(name)
    }

    private fun jsExtractTemplate(name: String): String = """
        /**
         * $name — DOM data extraction
         *
         * Runs in browser context via tab.eval or plugin resource.
         * Returns a JSON string with extracted data.
         */
        (function() {
            'use strict';

            var result = {
                url: window.location.href,
                title: document.title,
                timestamp: new Date().toISOString(),
                data: {}
            };

            // TODO: Extract data from the DOM
            // Example:
            // result.data.headings = Array.from(
            //   document.querySelectorAll('h1, h2, h3')
            // ).map(function(h) { return h.textContent.trim(); });

            return JSON.stringify(result, null, 2);
        })();
    """.trimIndent()

    private fun jsInjectTemplate(name: String): String = """
        /**
         * $name — DOM modification
         *
         * Runs in browser context via tab.eval.
         * Modifies the page DOM and returns a status object.
         */
        (function() {
            'use strict';

            var status = {
                modified: 0,
                errors: []
            };

            // TODO: Modify the DOM
            // Example:
            // var elements = document.querySelectorAll('.target');
            // elements.forEach(function(el) {
            //   el.style.display = 'none';
            //   status.modified++;
            // });

            return JSON.stringify(status, null, 2);
        })();
    """.trimIndent()

    private fun jsInteractTemplate(name: String): String = """
        /**
         * $name — Page interaction
         *
         * Runs in browser context via tab.eval.
         * Simulates user interaction and returns the result.
         */
        (function() {
            'use strict';

            var result = {
                success: false,
                action: '$name',
                details: {}
            };

            // TODO: Implement interaction
            // Example:
            // var btn = document.querySelector('#submit');
            // if (btn) {
            //   btn.click();
            //   result.success = true;
            //   result.details.clicked = '#submit';
            // } else {
            //   result.details.error = 'Button #submit not found';
            // }

            return JSON.stringify(result, null, 2);
        })();
    """.trimIndent()

    // ==================== Script ====================

    /**
     * Generate a simple build/deploy/run script.
     *
     * @param scriptType "build", "deploy", or "run"
     * @param shell "ps1" or "bash"
     */
    fun scriptScaffold(name: String, scriptType: String, shell: String): String = when (shell) {
        "ps1" -> ps1Template(name, scriptType)
        "bash" -> bashTemplate(name, scriptType)
        else -> ps1Template(name, scriptType)
    }

    private fun ps1Template(name: String, scriptType: String): String = """
        <#
        .SYNOPSIS
            $name — $scriptType script

        .DESCRIPTION
            ${scriptType.replaceFirstChar { it.uppercase() }} script for $name.

        .PARAMETER Verbose
            Show detailed output.
        #>
        param(
            [switch]${'$'}Verbose
        )

        ${'$'}ErrorActionPreference = "Stop"

        Write-Host "[$name] Starting $scriptType..."

        # TODO: Implement $scriptType logic
        # Example for build:
        #   & mvn -pl browser4-plugins/$name -am compile -DskipTests
        # Example for deploy:
        #   Copy-Item "target/*.jar" "${'$'}env:BROWSER4_HOME/plugins/" -Force

        if (${'$'}LASTEXITCODE -ne 0) {
            Write-Error "[$name] $scriptType failed with exit code ${'$'}LASTEXITCODE"
            exit 1
        }

        Write-Host "[$name] $scriptType completed successfully."
    """.trimIndent()

    private fun bashTemplate(name: String, scriptType: String): String = """
        #!/usr/bin/env bash
        # $name — $scriptType script
        set -euo pipefail

        echo "[$name] Starting $scriptType..."

        # TODO: Implement $scriptType logic
        # Example for build:
        #   mvn -pl browser4-plugins/$name -am compile -DskipTests
        # Example for deploy:
        #   cp target/*.jar "${'$'}BROWSER4_HOME/plugins/"

        echo "[$name] $scriptType completed successfully."
    """.trimIndent()

    // ==================== Helpers ====================

    /**
     * Convert kebab-case or snake_case to PascalCase.
     * "browser4-seo" -> "Browser4Seo"
     */
    fun toClassName(name: String): String =
        name.replace("-", " ").replace("_", " ")
            .split(" ").joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }

    /**
     * Convert kebab-case or PascalCase to camelCase.
     * "browser4-seo" -> "browser4Seo"
     */
    fun toCamelCase(name: String): String {
        val pascal = toClassName(name)
        return pascal.replaceFirstChar { it.lowercase() }
    }
}

