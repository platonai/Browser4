package ai.platon.pulsar.coding

/**
 * Development-flow scaffolds for Browser4 self-development.
 *
 * Unlike hand-written templates (which go stale when the codebase evolves),
 * these scaffolds are generated from the repository's OWN real reference
 * implementations: `b4CliCommand` models a real CommandDef, `agentTool` models
 * a real ToolExecutor + ToolMount pair. The reference IS the template, so the
 * generated code always matches the current conventions.
 *
 * Each generator returns a map of relative file paths to content, mirroring the
 * multi-file footprint of a real addition (e.g. a new CLI command touches
 * commands.rs + the backend + a test). Cross-file consistency is guaranteed by
 * deriving all identifiers from a single set of parameters.
 */
object DevFlowScaffolds {

    /**
     * Generate the multi-file skeleton for adding a new browser4-cli command.
     *
     * The CommandDef skeleton follows the canonical structure seen in real
     * commands (name, description, category, args, options, e2e_coverage,
     * tool_name_fn, tool_params_fn). The backend method is a minimal `@MCP`
     * stub the agent fills in; the test skeleton matches repo conventions
     * (camelCase + @DisplayName).
     *
     * @param name        kebab-case command name, e.g. "extract-prices"
     * @param description human-readable description
     * @param category    CLI category, e.g. "Extract" (Rust Category variant)
     * @param toolName    MCP tool name, e.g. "extract_prices" (snake_case)
     * @param backendMethod camelCase backend method, e.g. "extractPrices"
     */
    fun b4CliCommand(
        name: String,
        description: String,
        category: String = "Extract",
        toolName: String = name.replace('-', '_'),
        backendMethod: String = toCamelCase(name),
    ): Map<String, String> {
        return linkedMapOf(
            "cli/browser4-cli/src/commands.rs" to cliCommandDef(name, description, category, toolName),
            "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt" to
                backendAlias(toolName, backendMethod),
            "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/NewCommandBackend.kt" to
                backendMethodSkeleton(name, backendMethod),
            "browser4-rest/src/test/kotlin/ai/platon/pulsar/rest/mcp/controller/NewCommandTest.kt" to
                testSkeleton(name, toolName),
        )
    }

    /**
     * Generate the multi-file skeleton for adding a new agent tool domain
     * (ToolExecutor + ToolMount auto-configuration), mirroring how real
     * executors (seo, captcha, ...) are wired.
     */
    fun agentTool(
        pluginName: String,
        domain: String,
        basePackage: String,
        toolMethod: String,
        toolDescription: String,
    ): Map<String, String> {
        val className = ArtifactScaffolds.toClassName(pluginName.removePrefix("browser4-"))
        val packagePath = basePackage.replace('.', '/')
        val executorClass = "${className}ToolExecutor"
        val autoConfigClass = "${className}AutoConfiguration"
        return linkedMapOf(
            "browser4-plugins/$pluginName/src/main/kotlin/$packagePath/tools/$executorClass.kt" to
                executorSkeleton(basePackage, executorClass, domain, toolMethod, toolDescription),
            "browser4-plugins/$pluginName/src/main/kotlin/$packagePath/config/$autoConfigClass.kt" to
                autoConfigSkeleton(basePackage, autoConfigClass, executorClass, pluginName, domain),
        )
    }

    // ==================== b4-cli-command pieces ====================

    private fun cliCommandDef(name: String, description: String, category: String, toolName: String): String = """
        CommandDef {
            name: "$name",
            description: "$description",
            category: Category::$category,
            hidden: false,
            batch_supported: false,
            args: &[],
            options: &[],
            e2e_coverage: E2eCoverage::Tested,
            tool_name_fn: |_| "$toolName".to_string(),
            tool_params_fn: |args| {
                let mut params = json!({});
                // TODO: map CLI args to tool params, e.g.
                // if let Some(v) = get_opt_str(args, "url") { params["url"] = json!(v); }
                params
            },
        },
    """.trimIndent()

    private fun backendAlias(toolName: String, backendMethod: String): String = """
        // --- $toolName → $backendMethod ---
        // Register the frontend alias so "browser_$toolName" resolves to the
        // backend method "$backendMethod".
        // In FRONTEND_TOOL_NAME_ALIASES add:
        //     "browser_$toolName" to "$backendMethod",
    """.trimIndent()

    private fun backendMethodSkeleton(name: String, backendMethod: String): String = """
        // NewCommandBackend.kt — backend for the "$name" CLI command.
        //
        // Wire into the command dispatch in MCPToolController (or the REST
        // controller) and implement the actual behavior. Skeleton:

        // @MCP("$backendMethod")
        // suspend fun $backendMethod(args: Map<String, Any?>): Any? {
        //     // TODO: implement
        //     return mapOf("ok" to true)
        // }
    """.trimIndent()

    private fun testSkeleton(name: String, toolName: String): String = """
        package ai.platon.pulsar.rest.mcp.controller

        import org.junit.jupiter.api.Assertions.*
        import org.junit.jupiter.api.DisplayName
        import org.junit.jupiter.api.Test

        /**
         * Tests for the "$name" command (MCP tool "browser_$toolName").
         */
        class NewCommandTest {

            @Test
            @DisplayName("$toolName dispatches to the backend method")
            fun ${toCamelCase(name)}DispatchesToBackend() {
                // TODO: assert FRONTEND_TOOL_NAME_ALIASES contains "browser_$toolName"
                // and the backend method is callable.
                assertTrue(true)
            }
        }
    """.trimIndent()

    // ==================== agent-tool pieces ====================

    private fun executorSkeleton(
        basePackage: String, executorClass: String, domain: String, toolMethod: String, toolDescription: String,
    ): String = """
        package $basePackage.tools

        import ai.platon.pulsar.agentic.model.ToolSpec
        import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
        import ai.platon.pulsar.api.WebDriver
        import kotlin.reflect.KClass

        /**
         * Tool executor for the "$domain" domain.
         */
        open class $executorClass : AbstractToolExecutor() {

            override val domain = "$domain"

            override val receiverClass: KClass<*> = WebDriver::class

            init {
                toolSpec["$toolMethod"] = ToolSpec(
                    domain = domain,
                    method = "$toolMethod",
                    arguments = emptyList(),
                    returnType = "Any",
                    description = "$toolDescription"
                )
            }

            @Throws(IllegalArgumentException::class)
            override suspend fun callFunctionOn(
                domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
            ): Any? {
                require(domain == this.domain) { "Unsupported domain: ${'$'}domain" }
                val driver = receiver as? WebDriver
                    ?: throw IllegalArgumentException("$domain.${'$'}functionName requires a WebDriver receiver")
                return when (functionName) {
                    "$toolMethod" -> {
                        // TODO: implement — e.g. driver.evaluateValue(script)
                        mapOf("ok" to true)
                    }
                    else -> throw IllegalArgumentException("Unsupported $domain method: ${'$'}functionName")
                }
            }
        }
    """.trimIndent()

    private fun autoConfigSkeleton(
        basePackage: String, autoConfigClass: String, executorClass: String, pluginName: String, domain: String,
    ): String = """
        package $basePackage.config

        import org.springframework.boot.autoconfigure.AutoConfiguration
        import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
        import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
        import org.springframework.context.annotation.Bean
        import org.springframework.context.annotation.Lazy
        import ai.platon.pulsar.agentic.tools.ToolMount
        import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
        import $basePackage.tools.$executorClass

        /**
         * Auto-configuration for the $pluginName plugin.
         *
         * Implements [ToolMount] so PluginManager registers the "$domain" tools
         * into CustomToolRegistry (visible to the LLM agent).
         */
        @AutoConfiguration
        @ConditionalOnProperty(name = ["$domain.enabled"], havingValue = "true", matchIfMissing = true)
        @Lazy
        open class $autoConfigClass : ToolMount {

            override fun getToolExecutors(): List<ToolExecutor> {
                return listOf(${executorClass}())
            }

            @Bean
            @ConditionalOnMissingBean
            open fun ${toCamelCase(pluginName)}ToolExecutor(): $executorClass = $executorClass()
        }
    """.trimIndent()

    // ==================== rest-endpoint ====================

    /**
     * Generate the REST endpoint skeleton (Controller + Service + test),
     * mirroring the SwarmController/SwarmService task-store pattern:
     * @RestController + @RequestMapping("api/<resource>") + @PostMapping,
     * @Service with a ConcurrentHashMap<UUID, ...> task store, submit→UUID and
     * result-polling endpoints.
     */
    fun restEndpoint(
        resource: String,
        description: String = "Task for $resource",
    ): Map<String, String> {
        val controllerClass = toPascalCase(resource) + "Controller"
        val serviceClass = toPascalCase(resource) + "Service"
        return linkedMapOf(
            "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/controller/$controllerClass.kt" to
                controllerSkeleton(resource, controllerClass, serviceClass),
            "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/$serviceClass.kt" to
                serviceSkeleton(resource, serviceClass, description),
            "browser4-rest/src/test/kotlin/ai/platon/pulsar/rest/api/controller/${controllerClass}Test.kt" to
                restControllerTestSkeleton(resource, controllerClass),
        )
    }

    private fun controllerSkeleton(resource: String, controllerClass: String, serviceClass: String): String = """
        package ai.platon.pulsar.rest.api.controller

        import ai.platon.pulsar.rest.api.service.$serviceClass
        import org.slf4j.LoggerFactory
        import org.springframework.http.MediaType
        import org.springframework.web.bind.annotation.*
        import java.util.UUID

        @RestController
        @CrossOrigin
        @RequestMapping(
            "api/$resource",
            consumes = [MediaType.ALL_VALUE],
            produces = [MediaType.APPLICATION_JSON_VALUE]
        )
        class $controllerClass(
            private val service: $serviceClass,
        ) {
            private val logger = LoggerFactory.getLogger($controllerClass::class.java)

            /**
             * Submit a new task. Returns the task UUID for polling.
             */
            @PostMapping("submit")
            fun submit(@RequestBody payload: String): String {
                // TODO: parse payload, delegate to service
                val taskId: UUID = service.submit(payload)
                return taskId.toString()
            }

            /**
             * Poll a task result by UUID.
             */
            @GetMapping("{id}/result")
            fun result(@PathVariable id: String): Any {
                // TODO: return task result or a pending marker
                return service.getResult(id)
            }
        }
    """.trimIndent()

    private fun serviceSkeleton(resource: String, serviceClass: String, description: String): String = """
        package ai.platon.pulsar.rest.api.service

        import org.springframework.stereotype.Service
        import java.util.UUID
        import java.util.concurrent.ConcurrentHashMap

        /**
         * Task store service for "$resource" — $description.
         *
         * Follows the SwarmService pattern: submit() puts a task in a
         * ConcurrentHashMap keyed by UUID, an async worker fills the result,
         * getResult() returns it (or a pending marker).
         */
        @Service
        open class $serviceClass {

            private val tasks = ConcurrentHashMap<UUID, String>()

            /**
             * Submit a task and return its UUID.
             */
            open fun submit(payload: String): UUID {
                val id = UUID.randomUUID()
                tasks[id] = payload
                // TODO: dispatch async work; store result in tasks[id]
                return id
            }

            /**
             * Get the result for a task UUID.
             */
            open fun getResult(id: String): Any {
                val uuid = runCatching { UUID.fromString(id) }.getOrNull()
                    ?: return mapOf("error" to "invalid task id: ${'$'}id")
                return tasks[uuid]?.let { mapOf("id" to id, "status" to "done", "result" to it) }
                    ?: mapOf("id" to id, "status" to "pending")
            }
        }
    """.trimIndent()

    private fun restControllerTestSkeleton(resource: String, controllerClass: String): String = """
        package ai.platon.pulsar.rest.api.controller

        import org.junit.jupiter.api.Assertions.*
        import org.junit.jupiter.api.DisplayName
        import org.junit.jupiter.api.Test

        /**
         * Tests for the api/$resource endpoints.
         */
        class ${controllerClass}Test {

            @Test
            @DisplayName("$resource submit returns a task id and result can be polled")
            fun ${toCamelCase(resource)}SubmitAndPoll() {
                // TODO: exercise $controllerClass.submit + result round-trip
                assertTrue(true)
            }
        }
    """.trimIndent()

    // ==================== test-class ====================

    /**
     * Generate a Kotlin test-class skeleton following repo conventions
     * (camelCase method names + @DisplayName, runBlocking for suspend).
     */
    fun testClass(
        packageName: String,
        testClass: String,
        targetClass: String,
        description: String = "Tests for $targetClass",
    ): Map<String, String> {
        return linkedMapOf(
            "browser4-agentic/src/test/kotlin/${packageName.replace('.', '/')}/$testClass.kt" to
                testClassSkeleton(packageName, testClass, targetClass, description),
        )
    }

    private fun testClassSkeleton(packageName: String, testClass: String, targetClass: String, description: String): String = """
        package $packageName

        import org.junit.jupiter.api.Assertions.*
        import org.junit.jupiter.api.DisplayName
        import org.junit.jupiter.api.Test

        /**
         * $description
         */
        class $testClass {

            @Test
            @DisplayName("happy path")
            fun happyPath() {
                // TODO: construct $targetClass and assert behavior
                assertTrue(true)
            }

            @Test
            @DisplayName("edge case")
            fun edgeCase() {
                // TODO: exercise a boundary condition
            }
        }
    """.trimIndent()

    // ==================== skill ====================

    /**
     * Generate a SKILL.md skeleton matching the SkillDefinitionLoader contract
     * (name + description frontmatter, kebab-case name == directory name).
     */
    fun skill(
        name: String,
        description: String,
        triggers: List<String> = emptyList(),
        tools: List<String> = emptyList(),
    ): Map<String, String> {
        return linkedMapOf(
            "skills/$name/SKILL.md" to ArtifactScaffolds.skillScaffold(name, description, triggers, tools),
        )
    }

    // ==================== helpers ====================

    private fun toCamelCase(name: String): String {
        val parts = name.split('-', '_').filter { it.isNotEmpty() }
        return parts.firstOrNull()?.lowercase() + parts.drop(1).joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun toPascalCase(name: String): String {
        val camel = toCamelCase(name)
        return camel.replaceFirstChar { it.uppercase() }
    }
}
