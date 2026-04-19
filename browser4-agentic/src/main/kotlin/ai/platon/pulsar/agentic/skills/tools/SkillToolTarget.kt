package ai.platon.pulsar.agentic.skills.tools

import ai.platon.pulsar.agentic.skills.SkillContext
import ai.platon.pulsar.agentic.skills.SkillRegistry

/**
 * Target object for the [SkillToolExecutor].
 *
 * It owns a [SkillContext] and delegates actual execution to [SkillRegistry].
 * Put any shared resources into [context.sharedResources] so skills can access them.
 */
class SkillToolTarget(
    val context: SkillContext,
    val registry: SkillRegistry = SkillRegistry.instance,
) {
    suspend fun execute(skillId: String, params: Map<String, Any?>): Any? {
        // Tool calls use Any? values; SkillRegistry expects Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val casted = params as Map<String, Any>
        return registry.execute(skillId, context, casted)
    }
}
