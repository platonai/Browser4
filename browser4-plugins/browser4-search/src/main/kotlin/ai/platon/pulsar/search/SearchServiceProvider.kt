/**
 * Copyright (c) Vincent Zhang, ivincent.zhang@gmail.com, Platon.AI.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.platon.pulsar.search

/**
 * Supported web search service providers.
 *
 * Mirrors [ai.platon.pulsar.captcha.CaptchaServiceProvider] in shape:
 * each provider corresponds to one external API, and `NONE` covers the
 * "no provider configured" case so the AutoConfiguration can degrade
 * gracefully instead of failing to start.
 */
enum class SearchServiceProvider {
    /** Tavily — AI-focused web search API, the default. */
    TAVILY,

    /** Bocha (博查) — Chinese-friendly web search API. */
    BOCHA,

    /** No provider configured. */
    NONE
}
