package ai.platon.pulsar.agentic.skills.tools

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.skills.DefinitionBackedSkill
import ai.platon.pulsar.agentic.skills.SkillRegistry
import ai.platon.pulsar.agentic.skills.execution.SkillExecutionSupport
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.agentic.tools.specs.ToolCallSpecificationProvider
import ai.platon.pulsar.common.getLogger
import java.nio.file.Files
import kotlin.reflect.KClass

class SkillScriptToolExecutor(
    private val registry: SkillRegistry = SkillRegistry.instance,
) : AbstractToolExecutor(), ToolCallSpecificationProvider {
    private val logger = getLogger(this)
    override val domain: String = "skill"
    override val receiverClass: KClass<*> = SkillToolTarget::class
    init {
        toolSpec["readReference"] = ToolSpec(
            domain = domain,
            method = "readReference",
            arguments = listOf(
                ToolSpec.Arg("id", "String"),
                ToolSpec.Arg("path", "String")
            ),
            returnType = "String",
            description = "Read a reference file under references/ for a skill"
        )
        toolSpec["runScript"] = ToolSpec(
            domain = domain,
            method = "runScript",
            arguments = listOf(
                ToolSpec.Arg("id", "String"),
                ToolSpec.Arg("path", "String"),
                ToolSpec.Arg("args", "List<String>", "emptyList()"),
                ToolSpec.Arg("timeoutMillis", "Long", "10000"),
                ToolSpec.Arg("maxOutputChars", "Int", "20000")
            ),
            returnType = "Map<String, Any?>",
            description = "Run a script under scripts/ for a skill (requires allowed-tools in SKILL.md). Applies timeout and output size limits."
        )
    }
    override fun getToolCallSpecifications(): List<ToolSpec> = toolSpec.values.toList()
    @Suppress("UNUSED_PARAMETER")
    override suspend fun callFunctionOn(
        domain: String,
        functionName: String,
        args: Map<String, Any?>,
        receiver: Any
    ): Any? {
        require(domain == this.domain) { "Unsupported domain: $domain" }
        require(receiver is SkillToolTarget) { "Target must be a SkillToolTarget" }
        return when (functionName) {
            "readReference" -> {
                validateArgs(args, allowed = setOf("id", "path"), required = setOf("id", "path"), functionName)
                val id = paramString(args, "id", functionName)!!
                val path = paramString(args, "path", functionName)!!
                readReference(id, path)
            }
            "runScript" -> {
                validateArgs(
                    args,
                    allowed = setOf("id", "path", "args", "timeoutMillis", "maxOutputChars"),
                    required = setOf("id", "path"),
                    functionName
                )
                val id = paramString(args, "id", functionName)!!
                val path = paramString(args, "path", functionName)!!
                val scriptArgs = when (val raw = args["args"]) {
                    null -> emptyList()
                    is List<*> -> raw.mapNotNull { it?.toString() }
                    else -> throw IllegalArgumentException("args must be List<String> for $functionName")
                }

                val timeoutMillis = (args["timeoutMillis"] as? Number)?.toLong() ?: 10_000L
                val maxOutputChars = (args["maxOutputChars"] as? Number)?.toInt() ?: 20_000

                require(timeoutMillis in 1_000L..120_000L) { "timeoutMillis out of range (1000..120000): $timeoutMillis" }
                require(maxOutputChars in 1_000..200_000) { "maxOutputChars out of range (1000..200000): $maxOutputChars" }

                runScript(id, path, scriptArgs, timeoutMillis, maxOutputChars)
            }
            else -> throw IllegalArgumentException("Unsupported $domain method: $functionName(${args.keys})")
        }
    }
    private fun readReference(skillId: String, relativePath: String): String {
        val skill = registry.get(skillId)
            ?: throw IllegalArgumentException("Skill '$skillId' is not registered")
        require(skill is DefinitionBackedSkill) { "Skill '$skillId' is not resource-backed" }
        val definition = skill.definition
        val root = SkillExecutionSupport.skillRoot(definition)
        val normalizedRel = relativePath.replace('\\', '/')
        require(normalizedRel.startsWith("references/") || normalizedRel.startsWith("reference/")) {
            "Only references/ files can be read: $relativePath"
        }
        val p = SkillExecutionSupport.resolveInSkillRoot(root, normalizedRel)
        require(Files.exists(p) && Files.isRegularFile(p)) { "Reference file not found: $relativePath" }
        return Files.readString(p)
    }
    private fun runScript(
        skillId: String,
        relativePath: String,
        args: List<String>,
        timeoutMillis: Long,
        maxOutputChars: Int,
    ): Map<String, Any?> {
        val skill = registry.get(skillId)
            ?: throw IllegalArgumentException("Skill '$skillId' is not registered")
        require(skill is DefinitionBackedSkill) { "Skill '$skillId' is not resource-backed" }
        val definition = skill.definition
        val root = SkillExecutionSupport.skillRoot(definition)
        val normalizedRel = relativePath.replace('\\', '/')
        require(normalizedRel.startsWith("scripts/")) { "Only scripts/ files can be executed: $relativePath" }
        val scriptPath = SkillExecutionSupport.resolveInSkillRoot(root, normalizedRel)
        require(Files.exists(scriptPath) && Files.isRegularFile(scriptPath)) { "Script file not found: $relativePath" }
        // Security gate: require allowed-tools.
        val ext = scriptPath.fileName.toString().substringAfterLast('.', "")
        require(ext.equals("py", ignoreCase = true)) { "Unsupported script type: .$ext" }
        require(definition.allowedTools.any { it.equals("Python", true) || it.startsWith("Python", true) || it.equals("python", true) }) {
            "Script execution is not allowed for '$skillId'. Configure 'allowed-tools' in SKILL.md to enable it."
        }
        val command = mutableListOf("python", normalizedRel)
        command.addAll(args)
        logger.info("Running skill script: {} {}", skillId, normalizedRel)
        val pb = ProcessBuilder(command)
            .directory(root.toFile())
            .redirectErrorStream(true)
        val process = pb.start()
        val outputBuilder = StringBuilder()
        val reader = process.inputStream.bufferedReader()
        val startNanos = System.nanoTime()
        var truncated = false
        while (true) {
            // Drain available output without blocking indefinitely.
            while (reader.ready()) {
                val ch = reader.read()
                if (ch == -1) break
                if (outputBuilder.length < maxOutputChars) {
                    outputBuilder.append(ch.toChar())
                } else {
                    truncated = true
                }
            }
            val finished = process.waitFor(50, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (finished) break
            val elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000
            if (elapsedMillis > timeoutMillis) {
                process.destroyForcibly()
                return mapOf(
                    "skillId" to skillId,
                    "script" to normalizedRel,
                    "exitCode" to -1,
                    "output" to outputBuilder.toString(),
                    "timedOut" to true,
                    "truncated" to truncated
                )
            }
        }
        // Read remainder
        val remaining = reader.readText()
        if (remaining.isNotEmpty()) {
            val spaceLeft = maxOutputChars - outputBuilder.length
            if (spaceLeft > 0) {
                outputBuilder.append(remaining.take(spaceLeft))
            }
            if (remaining.length > spaceLeft) {
                truncated = true
            }
        }
        val exitCode = process.exitValue()
        return mapOf(
            "skillId" to skillId,
            "script" to normalizedRel,
            "exitCode" to exitCode,
            "output" to outputBuilder.toString(),
            "timedOut" to false,
            "truncated" to truncated
        )
    }
}
