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
package ai.platon.pulsar.protocol.browser.emulator

import ai.platon.pulsar.api.WebDriver
import ai.platon.pulsar.api.model.WebDriverException
import java.time.Duration

/**
 * How long a fetch refused by the snapshot origin guard waits before its next attempt.
 *
 * A refusal is a momentary, local conflict: another fetch advanced the tab between this
 * fetch's navigation and its snapshot. The refused driver is retired, so the retry runs
 * on a fresh driver/tab, and the conflict has usually cleared by the time the other fetch
 * finishes its own document - a wait measured in seconds. The default retry policy is a
 * remote-failure backoff of 30-45 s per retry, and three retries of it span two minutes:
 * longer than the deadline callers wait for a task (the swarm API polls a task for two
 * minutes), so a fetch refused twice could never finish inside any caller's deadline -
 * it ran out of its retry budget instead (408 RequestTimeout) and left the caller with a
 * task that was still "retrying" when it gave up.
 * */
val TAB_ORIGIN_MISMATCH_RETRY_DELAY: Duration = Duration.ofSeconds(10)

/**
 * The snapshot origin guard refused to record a document: the tab showed a document other
 * than the one this fetch navigated to, so a concurrent fetch advanced the tab in between
 * and the document's title and content can not be attributed to this fetch's URL.
 *
 * It is a [WebDriverException] on purpose, so the fetch pipeline handles it like any other
 * driver level failure - the driver is retired (the tab was taken over, it can not be
 * trusted for this fetch anymore) and the fetch is retried in the crawl scope on a fresh
 * driver/tab. Unlike a driver failure, the conflict is momentary and local, so that retry
 * is prompt, see [TAB_ORIGIN_MISMATCH_RETRY_DELAY].
 * */
class TabOriginMismatchException(
    message: String? = null,
    driver: WebDriver? = null,
) : WebDriverException(message, driver = driver)

/**
 * The retry delay for a fetch that failed with [e], or null to use the task runner's
 * default retry policy.
 *
 * Only a snapshot-origin refusal gets the prompt delay: a refused document is a local
 * conflict with another fetch, while the default policy is a backoff for remote failures,
 * see [TAB_ORIGIN_MISMATCH_RETRY_DELAY].
 * */
fun crawlRetryDelayFor(e: Throwable): Duration? =
    TAB_ORIGIN_MISMATCH_RETRY_DELAY.takeIf { e is TabOriginMismatchException }

class NavigateTaskCancellationException : IllegalStateException {
    constructor() : super() {}

    constructor(message: String) : super(message) {
    }

    constructor(message: String, cause: Throwable) : super(message, cause) {
    }

    constructor(cause: Throwable) : super(cause) {
    }
}

open class WebDriverPoolException(
    val browserId: String,
    override val message: String,
) : WebDriverException()

open class WebDriverPoolExhaustedException(
    browserId: String,
    message: String,
) : WebDriverPoolException(browserId, message)
