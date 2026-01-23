package ai.platon.pulsar.agentic.common

import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.RequiredDirectory
import java.nio.file.Path

object AgentPaths {

    @RequiredDirectory
    val AGENT_BASE_DIR: Path = AppContext.APP_DATA_DIR.resolve("agent")

    @RequiredDirectory
    val SKILLS_DIR: Path = AGENT_BASE_DIR.resolve("skills")

    init {
        AppPaths.createRequiredResources(AgentPaths::class)
    }
}
