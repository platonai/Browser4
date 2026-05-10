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
package ai.platon.browser4.driver.examples

import ai.platon.browser4.driver.chrome.ChromeLauncher
import ai.platon.browser4.driver.chrome.DevToolsConfig
import ai.platon.browser4.driver.chrome.common.ChromeOptions
import ai.platon.browser4.driver.common.BrowserSettings
import ai.platon.pulsar.common.browser.BrowserFiles
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

abstract class BrowserExampleBase(val headless: Boolean = false): AutoCloseable {
    val logger = LoggerFactory.getLogger(BrowserExampleBase::class.java)

    open val testUrl = "https://gitee.com/"

    val browserSettings = BrowserSettings()
    val preloadJs = browserSettings.scriptLoader.getPreloadJs()
    val launchOptions = ChromeOptions()
            .addArgument("window-size", formatViewPort())
            .also { it.headless = headless }
    val userDataDir = BrowserFiles.computeTestContextDir()
    val launcher = ChromeLauncher(userDataDir)

    val chrome = launcher.launch(launchOptions)
    val tab = chrome.createTab()
    val devTools = chrome.createDevTools(tab, DevToolsConfig())

    val browser get() = devTools.browser
    val network get() = devTools.network
    val page get() = devTools.page
    val mainFrame get() = runBlocking { page.getFrameTree().frame }
    val runtime get() = devTools.runtime
    val emulation get() = devTools.emulation
    val dom get() = devTools.dom
    val overlay get() = devTools.overlay

    abstract suspend fun run()

    val pageSource: String
        get() {
            val evaluation = runBlocking { runtime.evaluate("document.documentElement.outerHTML") }
            return evaluation.result.value.toString()
        }

    private fun formatViewPort(delimiter: String = ","): String {
        val vp = BrowserSettings.VIEWPORT
        return "${vp.width}$delimiter${vp.height}"
    }

    override fun close() {
        devTools.awaitTermination()
    }
}
