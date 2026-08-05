/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.platon.pulsar.tools

import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.config.CapabilityTypes
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.metadata.ParseStatusCodes
import ai.platon.pulsar.skeleton.workflow.parse.ParseFilters
import ai.platon.pulsar.skeleton.workflow.parse.ParseResult
import ai.platon.pulsar.skeleton.workflow.parse.Parser
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.BodyContentHandler
import java.time.Duration

typealias TikaMetadata = Metadata

class TikaParser(
    val parseFilters: ParseFilters? = null,
    val conf: ImmutableConfig
) : Parser {
    override val timeout: Duration =
        conf.getDuration(CapabilityTypes.PARSE_TIMEOUT, AppConstants.DEFAULT_MAX_PARSE_TIME)!!

    private val parser = AutoDetectParser()
    private val context = ParseContext()

    override fun parse(page: WebPage): ParseResult {
        val handler = BodyContentHandler()
        val metadata = TikaMetadata()
        parser.parse(page.contentAsInputStream, handler, metadata, context)
        metadata.names().forEach { name ->
            page.metadata.set(name, metadata.get(name))
        }
        return ParseResult(ParseStatusCodes.SUCCESS, ParseStatusCodes.SC_OK)
    }
}
