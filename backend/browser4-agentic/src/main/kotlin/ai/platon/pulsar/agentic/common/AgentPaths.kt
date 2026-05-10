package ai.platon.pulsar.agentic.common

import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.DateTimes
import ai.platon.pulsar.common.RequiredDirectory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

object AgentPaths {

    @RequiredDirectory
    val AGENT_BASE_DIR: Path = AppContext.APP_DATA_DIR.resolve("agent")

    @RequiredDirectory
    val SKILLS_DIR: Path = AGENT_BASE_DIR.resolve("skills")

    init {
        AppPaths.createRequiredResources(AgentPaths::class)
    }

    fun resolveTimedDirectory(time: Instant): Path {
        val path = AGENT_BASE_DIR
            .resolve(DateTimes.PATH_SAFE_YEAR.format(time))
            .resolve(DateTimes.PATH_SAFE_MONTH.format(time))
            .resolve(DateTimes.PATH_SAFE_DAY.format(time))

        Files.createDirectories(path)

        return path
    }
}
