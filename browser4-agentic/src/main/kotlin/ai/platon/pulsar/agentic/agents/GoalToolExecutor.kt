package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.agentic.model.ToolExample
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.agentic.tools.specs.ToolCallSpecificationProvider
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import kotlin.reflect.KClass

/**
 * The per-agent receiver of the `goal` domain: [GoalState] plus nothing else.
 *
 * Registered exactly like `memory`'s target ([ai.platon.pulsar.agentic.memory.MemoryToolTarget]):
 * one stateless executor in [ai.platon.pulsar.agentic.tools.CustomToolRegistry], one
 * target bound per agent, so two agents in one process never share a plan.
 */
class GoalToolTarget(val state: GoalState)

/**
 * Model-visible `goal.plan` — the only way a run can record a plan.
 *
 * | Method | Purpose |
 * |--------|---------|
 * | `goal.plan` | declare or update the run's plan (steps + done-when) |
 *
 * There is deliberately **no method that changes the goal**: the goal belongs to
 * the operator (`/goal`, `command.goal`). A run that cannot meet it says so in
 * `system.taskComplete(problems=[…])` or marks the step `dropped` with a reason;
 * redefining success is not a tool.
 *
 * The plan is engine state: the model does not need to read it back, because
 * every request ends with the pinned goal block that already carries the steps
 * and their markers (`docs-dev/copilot/agent-goal-mode.md` §4–§5).
 */
class GoalToolExecutor : AbstractToolExecutor(), ToolCallSpecificationProvider {

    companion object {
        /** Domain of the model-visible plan tool (`goal.plan`). */
        const val DOMAIN = "goal"
    }

    override val domain = DOMAIN
    override val receiverClass: KClass<*> = GoalToolTarget::class

    /** Falls back to "no goal in this run" instead of "no target object is available". */
    override val requiresReceiver: Boolean = false

    private val mapper = pulsarObjectMapper()

    init {
        toolSpec["plan"] = ToolSpec(
            domain = domain, method = "plan",
            arguments = listOf(
                ToolSpec.Arg(
                    "steps", "List",
                    null,
                    "The plan, in order. Each entry is {text, status?, reason?} where status is " +
                        "pending (default), done or dropped — a dropped step must carry a reason. " +
                        "A list of plain strings is accepted as pending steps.",
                ),
                ToolSpec.Arg(
                    "doneWhen", "List?",
                    "null",
                    "Your own acceptance criteria for this goal, e.g. \"the price appears in the " +
                        "answer\". Shown back to you every round; omit to keep the current ones.",
                ),
            ),
            returnType = "String",
            description = "Record or update the plan for the current goal. The goal itself is " +
                "pinned by the engine and cannot be changed from a tool call: if it cannot be " +
                "met, report that in system.taskComplete(problems=[...]) or mark the step " +
                "dropped with a reason. Mark a step done as soon as it is done; an unfinished " +
                "step fails the completion report.",
            examples = listOf(
                ToolExample(
                    title = "Declare a plan",
                    args = mapOf(
                        "steps" to """["open the product page","extract the price","write it to out/price.json"]""",
                        "doneWhen" to """["the price is in the answer and in out/price.json"]""",
                    ),
                ),
                ToolExample(
                    title = "Report progress",
                    args = mapOf(
                        "steps" to """[{"text":"open the product page","status":"done"},""" +
                            """{"text":"extract the price","status":"done"},""" +
                            """{"text":"write it to out/price.json","status":"pending"}]""",
                    ),
                ),
                ToolExample(
                    title = "Drop a step honestly",
                    args = mapOf(
                        "steps" to """[{"text":"open the product page","status":"done"},""" +
                            """{"text":"extract the price","status":"dropped",""" +
                            """"reason":"the page is a 404; the product no longer exists"}]""",
                    ),
                ),
            ),
        )
    }

    override fun getToolCallSpecifications(): List<ToolSpec> = getToolSpecs().values.toList()

    override suspend fun callFunctionOn(
        domain: String, functionName: String, args: Map<String, Any?>, receiver: Any,
    ): Any? {
        require(domain == this.domain) { "Unsupported domain: $domain" }
        val state = (receiver as? GoalToolTarget)?.state
            ?: throw IllegalStateException(
                "No goal is active in this run (goal mode is off, or the task has already ended)"
            )

        return when (functionName) {
            "plan" -> handlePlan(state, args)
            else -> throw IllegalArgumentException("Unsupported goal method: $functionName(${args.keys})")
        }
    }

    /**
     * Apply one plan update and answer with a compact progress envelope.
     *
     * The model already sees the plan in the pinned block of the next request, so
     * the result only confirms the update and states the remaining obligation —
     * echoing 12 steps back would spend context to say nothing new.
     */
    private fun handlePlan(state: GoalState, args: Map<String, Any?>): String {
        val steps = parseSteps(args["steps"], "plan")
        val doneWhen = args["doneWhen"]?.let { parseDoneWhen(it, "plan") }
        val goal = try {
            state.applyPlan(steps, doneWhen)
        } catch (e: IllegalArgumentException) {
            state.lastError = e.message
            throw e
        }

        val envelope = linkedMapOf<String, Any?>(
            "ok" to true,
            "goalRevision" to goal.revision,
            "planRevision" to goal.planRevision,
            "steps" to goal.steps.size,
            "done" to goal.doneSteps.size,
            "pending" to goal.openSteps.size,
            "dropped" to goal.droppedSteps.size,
            "note" to if (goal.openSteps.isEmpty()) {
                "The plan is complete. Report the result with system.taskComplete."
            } else {
                "The goal is pinned in every request. Finish the pending steps, or mark each " +
                    "one dropped with a reason before finishing."
            },
        )
        return mapper.writeValueAsString(envelope)
    }

    /**
     * Steps arrive as JSON from a native tool call, so accept the shapes a model
     * actually produces: `[{text, status, reason}]`, `["a","b"]`, and a single
     * comma-separated string (the CLI path renders some lists that way).
     */
    private fun parseSteps(raw: Any?, functionName: String): List<GoalStep> {
        if (raw == null) throw IllegalArgumentException("Missing parameter 'steps' for $functionName")
        val entries: List<Any?> = when (raw) {
            is List<*> -> raw
            is Array<*> -> raw.toList()
            is String -> raw.split('\n', ',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            else -> throw IllegalArgumentException(
                "Parameter 'steps' must be a list for $functionName | actual='${raw}'"
            )
        }
        require(entries.size <= GoalConfig.maxSteps() * 4) {
            "goal.plan accepts at most ${GoalConfig.maxSteps() * 4} steps; ${entries.size} were sent"
        }
        return entries.map { entry ->
            when (entry) {
                is Map<*, *> -> {
                    val text = (entry["text"] ?: entry["step"] ?: entry["title"])?.toString().orEmpty()
                    val status = GoalStepStatus.parse(
                        (entry["status"] ?: entry["state"])?.toString()
                    ) ?: GoalStepStatus.PENDING
                    val reason = (entry["reason"] ?: entry["why"])?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    GoalStep(text = text, status = status, reason = reason)
                }
                is String -> GoalStep(text = entry)
                else -> throw IllegalArgumentException(
                    "Each entry of 'steps' must be an object or a string for $functionName | actual='$entry'"
                )
            }
        }
    }

    private fun parseDoneWhen(raw: Any?, functionName: String): List<String> = when (raw) {
        is List<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf { text -> text.isNotEmpty() } }
        is Array<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf { text -> text.isNotEmpty() } }
        is String -> raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        else -> throw IllegalArgumentException(
            "Parameter 'doneWhen' must be a list for $functionName | actual='${raw}'"
        )
    }
}
