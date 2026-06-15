package ai.platon.browser4.chrome.dom

import ai.platon.pulsar.browser.common.CDTReflectiveMapper
import ai.platon.pulsar.chrome.dom.model.*

object DOMSerializer {
    fun toJson(root: SerializableDOMTree): String = CDTReflectiveMapper.serialize(root)
    fun toJson(browserState: BrowserState): String = CDTReflectiveMapper.serialize(browserState)
    fun toJson(tabsState: List<TabState>): String = CDTReflectiveMapper.serialize(tabsState)
    fun toJson(nano: NanoDOMTree): String = CDTReflectiveMapper.serialize(nano)
    fun toYaml(nano: NanoDOMTree): String = CDTReflectiveMapper.serializeToYaml(nano)
    fun toJson(nodes: InteractiveDOMTreeNodeList): String = CDTReflectiveMapper.serialize(nodes)
}
