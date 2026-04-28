package ai.platon.browser4.driver.chrome.dom

import ai.platon.browser4.driver.chrome.dom.model.*
import ai.platon.pulsar.common.serialize.json.doubleBindModule
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

object DOMSerializer {
    val MAPPER: ObjectMapper = jacksonObjectMapper().apply {
        setDefaultPropertyInclusion(JsonInclude.Include.NON_EMPTY)
        registerModule(doubleBindModule())
    }

    val YAML_MAPPER: ObjectMapper =
        ObjectMapper(YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)).apply {
            registerModule(KotlinModule.Builder().build())
            setDefaultPropertyInclusion(JsonInclude.Include.NON_EMPTY)
            registerModule(doubleBindModule())
        }

    fun toJson(root: SerializableDOMTree): String {
        return MAPPER.writeValueAsString(root)
    }

    fun toJson(browserState: BrowserState): String {
        return MAPPER.writeValueAsString(browserState)
    }

    fun toJson(tabsState: List<TabState>): String {
        return MAPPER.writeValueAsString(tabsState)
    }

    // serialize nano tree
    fun toJson(nano: NanoDOMTree): String {
        return MAPPER.writeValueAsString(nano)
    }

    // serialize nano tree
    fun toYaml(nano: NanoDOMTree): String {
        return YAML_MAPPER.writeValueAsString(nano)
    }

    // serialize nano tree
    fun toJson(nodes: InteractiveDOMTreeNodeList): String {
        return MAPPER.writeValueAsString(nodes)
    }
}
