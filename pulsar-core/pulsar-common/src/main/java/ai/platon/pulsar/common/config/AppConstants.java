/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.platon.pulsar.common.config;

import ai.platon.pulsar.common.math.geometric.DimI;
import ai.platon.pulsar.common.measure.ByteUnit;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@SuppressWarnings("unused")
public interface AppConstants {

    String PULSAR_CONTEXT_CONFIG_LOCATION = "classpath*:/pulsar-beans/app-context.xml";
    String QL_CONTEXT_CONFIG_LOCATION = "classpath*:/ql-beans/app-context.xml";
    String AGENTIC_CONTEXT_CONFIG_LOCATION = "classpath*:/agentic-beans/app-context.xml";

    String YES_STRING = "y";

    String VAR_REFRESH = "refresh";
    String VAR_CAPTURE = "capture";

    /**
     * An example of the shortest accessible url: <a href="http://t.tt">http://t.tt</a>
     * <p>
     * Another example is <a href="http://a.co">http://a.co</a> (redirect to amazon.com)
     *
     */
    String SHORTEST_VALID_URL = "http://t.tt";
    /**
     * The length of the shortest example url
     *
     */
    int SHORTEST_VALID_URL_LENGTH = SHORTEST_VALID_URL.length();
    /**
     * The example url
     *
     */
    String EXAMPLE_URL = "https://example.com";
    /**
     * The example url
     *
     */
    List<String> SEARCH_ENGINE_URLS = List.of(
            "https://www.google.com/",
            "https://www.bing.com/",
            "https://cn.bing.com/",
            "https://www.baidu.com/",
            "https://www.so.com/"
    );
    String SEARCH_ENGINE_URL = "https://cn.bing.com/";
    String SEARCH_ENGINE_EN_URL = "https://cn.bing.com/?ensearch=1";
    /**
     * The prefix of all pulsar specified urls
     *
     */
    String INTERNAL_URL_PREFIX = "http://internal.platon.ai";
    /**
     * The url of the nil page
     *
     */
    String NIL_PAGE_URL = INTERNAL_URL_PREFIX + "/nil";

    /**
     * Storage
     *
     */
    String MEM_STORE_CLASS = "org.apache.gora.memory.store.MemStore";
    /**
     * A minimal file backend store
     */
    String FILE_BACKEND_STORE_CLASS = "ai.platon.pulsar.persist.gora.FileBackendPageStore";
    String MONGO_STORE_CLASS = "org.apache.gora.mongodb.store.MongoStore";
    // schema version 1.10.x
//    String WEBPAGE_SCHEMA = "webpage110";
    /**
     * Schema version 1.12.x, has to be compatible with webpage110
     *
     */
    String WEBPAGE_SCHEMA = "webpage120";

    /**
     * Fetch
     *
     */
    int DISTANCE_INFINITE = 10000;
    Duration FETCH_TASK_TIMEOUT_DEFAULT = Duration.ofMinutes(10);

    /**
     * Parse
     *
     */
    Instant TCP_IP_STANDARDIZED_TIME = Instant.parse("1982-01-01T00:00:00Z");
    Instant MIN_ARTICLE_PUBLISH_TIME = Instant.parse("1995-01-01T00:00:00Z");
    Duration DEFAULT_MAX_PARSE_TIME = Duration.ofSeconds(60);

    int MAX_LINK_PER_PAGE = 4000;

    /**
     * Local file commands
     *
     */
    String CMD_PROXY_POOL_DUMP = "dump-proxy-pool";

    String CMD_PROXY_FORCE_IDLE = "IPS-force-idle";

    /**
     * Browser
     *
     */
    int DEFAULT_BROWSER_MAX_OPEN_TABS = 50;

    double BROWSER_TAB_REQUIRED_MEMORY = ByteUnit.GIB.toBytes(1.5); // at least 1.5 GiB to open a new tab

    double DEFAULT_BROWSER_RESERVED_MEMORY = ByteUnit.GIB.toBytes(2.0); // 3 GiB

    double DEFAULT_BROWSER_RESERVED_MEMORY_MIB = ByteUnit.BYTE.toMiB(DEFAULT_BROWSER_RESERVED_MEMORY); // 5 GiB

    Duration POLLING_DRIVER_TIMEOUT_DEFAULT = Duration.ofSeconds(60);

    DimI DEFAULT_VIEWPORT = new DimI(1920, 1080);
    String PULSAR_META_INFORMATION_ID = "PulsarMetaInformation";
    String PULSAR_META_INFORMATION_SELECTOR = "#" + PULSAR_META_INFORMATION_ID;
    String PULSAR_SCRIPT_SECTION_ID = "PulsarScriptSection";
    String PULSAR_SCRIPT_SECTION_SELECTOR = "#" + PULSAR_SCRIPT_SECTION_ID;
    String PULSAR_DOCUMENT_NORMALIZED_URI = "normalizedURI";
    String PULSAR_ATTR_HIDDEN = "_h";
    String PULSAR_ATTR_OVERFLOW_HIDDEN = "_oh";
    String PULSAR_ATTR_OVERFLOW_VISIBLE = "_visible";
    String PULSAR_ATTR_ELEMENT_NODE_VI = "vi";
    String PULSAR_ATTR_TEXT_NODE_VI = "tv";

    String PULSAR_ATTR_COMPUTED_STYLE = "cs";
    String PULSAR_ATTR_ELEMENT_NODE_DATA = "nd";

    String BROWSER_INTERACTIVE_ELEMENTS_SELECTOR = "a, button, input, select, textarea, " +
            "[role='button'], [role='link'], [onclick], [onmousedown], [onmouseup]";

    /**
     * Other notable properties:
     * overflow
     * text-overflow
     *
     */
    String CLIENT_JS_PROPERTY_NAMES = "font-size, color, background-color";

    /**
     * Metrics
     *
     */
    String DEFAULT_METRICS_NAME = "browser4";

    /**
     * SQL engine
     *
     */
    String H2_SESSION_FACTORY = "ai.platon.pulsar.ql.h2.H2SessionFactory";

    /**
     * Local file base url, the host is a fake host.
     *
     */
    String LOCAL_FILE_BASE_URL = "http://localfile.internal";

    String BROWSER_INTERNAL_BASE_URL = "http://browser.internal";
}
