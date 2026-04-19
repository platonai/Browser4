package ai.platon.pulsar.agentic.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class SkillSpecComplianceTest {

    @Test
    fun invalidNameShouldBeRejected() {
        val root = createTempDirectory("skills-")
        val dir = Files.createDirectory(root.resolve("BadName"))
        dir.resolve("SKILL.md").writeText(
            """
            ---
            name: BadName
            description: x
            ---

            body
            """.trimIndent()
        )

        val defs = SkillDefinitionLoader().loadFromDirectory(root)
        assertEquals(0, defs.size)
    }

    @Test
    fun nameMustMatchDirectoryName() {
        val root = createTempDirectory("skills-")
        val dir = Files.createDirectory(root.resolve("dir-name"))
        dir.resolve("SKILL.md").writeText(
            """
            ---
            name: other-name
            description: x
            ---

            body
            """.trimIndent()
        )

        val defs = SkillDefinitionLoader().loadFromDirectory(root)
        assertEquals(0, defs.size)
    }

    @Test
    fun consecutiveHyphensShouldBeRejected() {
        val root = createTempDirectory("skills-")
        val dir = Files.createDirectory(root.resolve("pdf--processing"))
        dir.resolve("SKILL.md").writeText(
            """
            ---
            name: pdf--processing
            description: x
            ---

            body
            """.trimIndent()
        )

        val defs = SkillDefinitionLoader().loadFromDirectory(root)
        assertEquals(0, defs.size)
    }

    @Test
    fun descriptionTooLongShouldBeRejected() {
        val root = createTempDirectory("skills-")
        val dir = Files.createDirectory(root.resolve("too-long-desc"))
        val longDesc = "a".repeat(1025)
        dir.resolve("SKILL.md").writeText(
            """
            ---
            name: too-long-desc
            description: $longDesc
            ---

            body
            """.trimIndent()
        )

        val defs = SkillDefinitionLoader().loadFromDirectory(root)
        assertEquals(0, defs.size)
    }
}
