package ai.platon.pulsar.common.sql

import org.apache.commons.lang3.RandomStringUtils

class SQLTemplate(
    val template: String,
    val name: String = generatedName,
) {
    var resource: String? = null

    val display: String get() = generateDisplay(resource, name)

    fun createSQL(url: String) = createInstance(url).sql

    fun createInstance(url: String) = SQLInstance(url, this, name)

    override fun toString() = template

    companion object {
        private val generatedName: String = RandomStringUtils.secure().nextAlphabetic(4)

        private fun generateDisplay(resource: String?, name: String): String {
            return resource?.substringAfterLast("/") ?: name
        }

        fun load(resource: String, name: String = generatedName): SQLTemplate {
            return SQLTemplate(SQLUtils.loadSQL(resource), name = name).also { it.resource = resource }
        }
    }
}
