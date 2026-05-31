/*-
 * #%L
 * cdt-kotlin-client
 * %%
 * Copyright (C) 2025 platon.ai
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package ai.platon.pulsar.driver.chrome.impl

import ai.platon.cdt.kt.protocol.types.accessibility.AXNode
import ai.platon.pulsar.chrome.handler.transport.EventDispatcher
import ai.platon.pulsar.common.ResourceLoader
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ProtocolAXTreeTest {

    @Test
    @DisplayName("Given AXTree json then deserialize correctly by OBJECT_MAPPER")
    fun givenAxtreeJsonThenDeserializeCorrectlyByObjectMapper() {
        val mapper = EventDispatcher.OBJECT_MAPPER
        val json = ResourceLoader.readString("dom/AXTree.json")
        val jsonNode = mapper.readTree(json)
        val jsonNodes = jsonNode.get("result").get("nodes")
        val nodes: List<AXNode> = mapper.readerFor(List::class.java).readValue(jsonNodes)

        Assertions.assertTrue { nodes.isNotEmpty() }
    }

    @Test
    @DisplayName("Given AXTree json then deserialize correctly by EventDispatcher")
    fun givenAxtreeJsonThenDeserializeCorrectlyByEventDispatcher() {
        val mapper = EventDispatcher.OBJECT_MAPPER
        val dispatcher = EventDispatcher()
        val str = ResourceLoader.readString("dom/AXTree.json")
        val json = dispatcher.patchMessageForProtocolChange(str)
        // printlnPro(this, json)

        val jsonNode = mapper.readTree(json)
        val jsonNodes = jsonNode.get("result").get("nodes")
        // Deserialize a List<AXNode> using the generic-aware overload
        @Suppress("UNCHECKED_CAST")
        val nodes = dispatcher.deserialize(arrayOf(AXNode::class.java), List::class.java, jsonNodes) as List<AXNode>

        Assertions.assertTrue { nodes.isNotEmpty() }
    }

    @Test
    @DisplayName("Given AXTree json WITH BAD FIELDS then deserialize correctly by EventDispatcher")
    fun givenAxtreeJsonWithBadFieldsThenDeserializeCorrectlyByEventDispatcher() {
        val mapper = EventDispatcher.OBJECT_MAPPER
        val json = ResourceLoader.readString("dom/AXTree.json")
            .replace("uninteresting", "UNINTERESTINGREPLACEDFORTEST")

        val jsonNode = mapper.readTree(json)
        val jsonNodes = jsonNode.get("result").get("nodes")
        val dispatcher = EventDispatcher()
        // Deserialize a List<AXNode> using the generic-aware overload
        @Suppress("UNCHECKED_CAST")
        val nodes = dispatcher.deserialize(arrayOf(AXNode::class.java), List::class.java, jsonNodes) as List<AXNode>

        Assertions.assertTrue { nodes.isNotEmpty() }
    }
}
