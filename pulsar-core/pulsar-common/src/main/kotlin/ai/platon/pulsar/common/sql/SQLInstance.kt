package ai.platon.pulsar.common.sql

import org.apache.commons.lang3.RandomStringUtils

class SQLInstance(
    val url: String,
    val template: SQLTemplate,
    val name: String = template.name,
) {
    val sql = createSQL()

    override fun toString() = sql

    private fun createSQL(): String {
        val sanitizedUrl = SQLUtils.sanitizeUrl(url)
        return template.template.replace("{{url}}", sanitizedUrl)
            .replace("@url", "'$sanitizedUrl'")
            .replace("{{snippet: url}}", "'$sanitizedUrl'")
    }

    companion object {
        private val generatedName: String = RandomStringUtils.secure().nextAlphabetic(4)

        fun load(url: String, resource: String, name: String = generatedName): SQLInstance {
            val template = SQLTemplate.load(resource, name = name)
            return SQLInstance(url, template, name)
        }
    }
}
