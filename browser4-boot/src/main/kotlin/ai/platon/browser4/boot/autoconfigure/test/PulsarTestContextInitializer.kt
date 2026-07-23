package ai.platon.browser4.boot.autoconfigure.test

import ai.platon.browser4.api.model.BrowserSettings
import ai.platon.pulsar.common.Systems
import ai.platon.pulsar.common.browser.BrowserProfileMode
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.skeleton.common.options.LoadOptionDefaults
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.support.AbstractApplicationContext

class PulsarTestContextInitializer : ApplicationContextInitializer<AbstractApplicationContext> {
    override fun initialize(applicationContext: AbstractApplicationContext) {
        println("Initializing Pulsar test context...")

        // Use temporary browsers to avoid browser conflicts, and also gain better performance
        BrowserSettings.withBrowserContextMode(BrowserProfileMode.TEMPORARY)
        Systems.setPropertyIfAbsent("app.name", "browser4")

        /**
         * Load options are in webpage scope, so it should be initialized after PulsarContextInitializer
         * */
        LoadOptionDefaults.apply {
            parse = true
            ignoreFailure = true
            nJitRetry = 3
            test = 1
            browser = BrowserType.PULSAR_CHROME
        }
    }
}
