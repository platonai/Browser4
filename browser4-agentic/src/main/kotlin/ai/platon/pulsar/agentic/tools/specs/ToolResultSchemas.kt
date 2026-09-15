package ai.platon.pulsar.agentic.tools.specs

/**
 * Result contracts of the tools whose payload is a JSON object.
 *
 * Requirement 6 says a client should be able to trust a documented result shape;
 * that only means something if the schema is written **against the data class the
 * tool actually serializes**. Two rules keep these honest:
 *
 * - **Nullability decides membership.** Jackson writes `null` for a nullable
 *   property unless the class says otherwise, and [ToolResultValidator] treats an
 *   explicit JSON `null` as a missing required field *and* as a type violation.
 *   So a nullable property appears in `properties` only when its class is
 *   annotated `@JsonInclude(NON_NULL)` (then it is simply absent when empty), and
 *   is left out entirely otherwise — a documented field nobody can rely on is
 *   worse than an undocumented one.
 * - **`required` lists only what is always written.** Defaults are non-null Kotlin
 *   values, so they are always present.
 *
 * The shapes are pinned by tests that run the real handler and validate its answer
 * against the schema declared here (`ExperienceToolExecutorTest`,
 * `MemoryToolExecutorTest`), so a payload change that outgrows its contract fails
 * the build instead of quietly producing `schema_violation` warnings in production.
 *
 * An `Instant` property (`last_verified`) is deliberately not typed: its wire form
 * depends on the mapper's JavaTime configuration, and a guess would either reject
 * valid results or document a lie.
 */
object ToolResultSchemas {

    /** `experience.query` → [ai.platon.pulsar.agentic.tools.experience.ExperienceQueryResult]. */
    const val EXPERIENCE_QUERY = """{"type":"object",""" +
        """"required":["tier","confidence"],"properties":{""" +
        """"tier":{"type":"string"},"confidence":{"type":"number"},""" +
        """"domain":{"type":"string"},"intent":{"type":"string"},"url_pattern":{"type":"string"},""" +
        """"page_type":{"type":"string"},"task_type":{"type":"string"},"summary":{"type":"string"},""" +
        """"extraction_query":{"type":"string"},"status":{"type":"string"},""" +
        """"primary_selectors":{"type":"object"},""" +
        """"warnings":{"type":"array","items":{"type":"string"}},""" +
        """"known_blockers":{"type":"array"},"steps":{"type":"array"}}}"""

    /** `experience.list` → [ai.platon.pulsar.agentic.tools.experience.ExperienceListResult]. */
    const val EXPERIENCE_LIST = """{"type":"object",""" +
        """"required":["total","page","page_size","total_pages","entries"],"properties":{""" +
        """"total":{"type":"integer"},"page":{"type":"integer"},"page_size":{"type":"integer"},""" +
        """"total_pages":{"type":"integer"},"entries":{"type":"array","items":{""" +
        """"type":"object","required":["domain"],"properties":{""" +
        """"domain":{"type":"string"},"intent":{"type":"string"},"status":{"type":"string"},""" +
        """"retrieval_tier":{"type":"string"},"confidence":{"type":"number"},""" +
        """"site_types":{"type":"array"},"page_patterns":{"type":"array"},"task_types":{"type":"array"},""" +
        """"success_count":{"type":"integer"},"failure_count":{"type":"integer"}}}}}}"""

    /**
     * `experience.save` → [ai.platon.pulsar.agentic.tools.experience.ExperienceSaveResult].
     *
     * `failure_category` is serialized even when empty (`@JsonInclude(ALWAYS)`), so it
     * is never required: the null it carries would read as a missing field.
     */
    const val EXPERIENCE_SAVE = """{"type":"object",""" +
        """"required":["saved","domain","outcome","confidence","retrieval_tier"],"properties":{""" +
        """"saved":{"type":"boolean"},"domain":{"type":"string"},"intent":{"type":"string"},""" +
        """"task_type":{"type":"string"},"outcome":{"type":"string"},"trace_path":{"type":"string"},""" +
        """"confidence":{"type":"number"},"retrieval_tier":{"type":"string"},"message":{"type":"string"},""" +
        """"facts_merged":{"type":"boolean"},"facts_status":{"type":"string"},""" +
        """"facts_rejected":{"type":"boolean"},"facts_message":{"type":"string"}}}"""

    /** `experience.deep_learn` → [ai.platon.pulsar.agentic.tools.experience.DeepLearnResult]. */
    const val EXPERIENCE_DEEP_LEARN = """{"type":"object",""" +
        """"required":["completed","domain","intent","status_after","promoted","new_confidence","selectors_found"],""" +
        """"properties":{"completed":{"type":"boolean"},"domain":{"type":"string"},"intent":{"type":"string"},""" +
        """"status_after":{"type":"string"},"promoted":{"type":"boolean"},"new_confidence":{"type":"number"},""" +
        """"message":{"type":"string"},"selectors_found":{"type":"integer"}}}"""

    /**
     * `skill.list` → `List<SkillRegistry.SkillSummary>`.
     *
     * The one advertised tool whose result is a **top-level array**, which is why
     * the contract matrix accepts `type: array` as well as `type: object` — a client
     * that only understood object results could not have described this tool at all.
     * `tags` is a set on the Kotlin side and a JSON array on the wire.
     */
    const val SKILL_LIST = """{"type":"array","items":{"type":"object",""" +
        """"required":["id","name","description","version","tags"],"properties":{""" +
        """"id":{"type":"string"},"name":{"type":"string"},"description":{"type":"string"},""" +
        """"version":{"type":"string"},"tags":{"type":"array","items":{"type":"string"}}}}}"""

    /**
     * `skill.info` → `SkillService.SkillDetail`.
     *
     * `scriptsPath`/`referencesPath`/`assetsPath`/`origin` are nullable Kotlin
     * properties without `@JsonInclude(NON_NULL)`, so they are left undocumented
     * rather than typed (see the class note on nullability).
     */
    const val SKILL_INFO = """{"type":"object",""" +
        """"required":["id","name","version","description","author","tags","dependencies","skillMd"],""" +
        """"properties":{"id":{"type":"string"},"name":{"type":"string"},"version":{"type":"string"},""" +
        """"description":{"type":"string"},"author":{"type":"string"},"skillMd":{"type":"string"},""" +
        """"tags":{"type":"array","items":{"type":"string"}},""" +
        """"dependencies":{"type":"array","items":{"type":"string"}}}}"""

    /**
     * `webdb.export` → the per-URL summary built by the executor.
     *
     * `error` is only written for a failed entry, so it is not required.
     */
    const val WEBDB_EXPORT = """{"type":"object",""" +
        """"required":["total","succeeded","failed","results"],"properties":{""" +
        """"total":{"type":"integer"},"succeeded":{"type":"integer"},"failed":{"type":"integer"},""" +
        """"results":{"type":"array","items":{"type":"object","required":["url","status"],"properties":{""" +
        """"url":{"type":"string"},"status":{"type":"string"},"error":{"type":"string"}}}}}}"""

    /** `memory.search` → [ai.platon.pulsar.agentic.memory.SearchPage]. */    const val MEMORY_SEARCH = """{"type":"object","required":["hits"],"properties":{""" +
        """"hits":{"type":"array","items":{"type":"object",""" +
        """"required":["taskId","ts","snippet","tier"],"properties":{""" +
        """"taskId":{"type":"string"},"ts":{"type":"integer"},"snippet":{"type":"string"},""" +
        """"tier":{"type":"string"}}}}}}"""

    /**
     * `memory.read` → [ai.platon.pulsar.agentic.memory.EventWindow].
     *
     * Only the four fields every [ai.platon.pulsar.agentic.memory.MemoryEvent] subtype
     * carries are typed; the per-subtype payload is part of the event vocabulary, not
     * of this window's contract.
     */
    const val MEMORY_READ = """{"type":"object","required":["taskId","events"],"properties":{""" +
        """"taskId":{"type":"string"},"events":{"type":"array","items":{"type":"object",""" +
        """"required":["seq","ts","agentUuid","taskId"],"properties":{""" +
        """"seq":{"type":"integer"},"ts":{"type":"integer"},""" +
        """"agentUuid":{"type":"string"},"taskId":{"type":"string"}}}}}}"""
}
