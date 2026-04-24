package ai.platon.pulsar.skeleton.common.options

import ai.platon.pulsar.common.DateTimes
import ai.platon.pulsar.common.browser.BrowserType
import java.time.temporal.ChronoUnit

/**
 * The default load options, be careful if you have to change the default behaviour.
 * */
object LoadOptionDefaults {
    /**
     * The default expiry time, some time we may need expire all pages by default, for example, in test mode
     * */
    val EXPIRES = ChronoUnit.DECADES.duration

    /**
     * The default time to expire
     * */
    val EXPIRE_AT = DateTimes.doomsday

    /**
     * Lazy flush.
     * */
    const val LAZY_FLUSH = true

    /**
     * Trigger the parse phase or not.
     *
     * Do not parse by default, since there are may ways to trigger a webpage parsing:
     * 1. use session.parse()
     * 2. add a -parse option
     * 3. use a [ai.platon.pulsar.crawl.common.url.ParsableHyperlink]
     * */
    const val PARSE = false

    /**
     * Store webpage content or not.
     *
     * Store webpage content by default.
     * If we are running a public cloud, this option might be changed to false.
     * */
    const val STORE_CONTENT = true
    /**
     * If true, still fetch the page even if it is gone.
     * */
    const val IGNORE_FAILURE = false

    /**
     * There are several cases to enable jit retry.
     * For example, in a test environment.
     * */
    const val N_JIT_RETRY = -1

    /**
     * The default browser is chrome with pulsar implemented web driver.
     * */
    val BROWSER = BrowserType.PULSAR_CHROME

    /**
     * Set to be > 0 if we are doing unit test or other test.
     * We will talk more, log more and trace more in test mode.
     * */
    const val TEST = 0

    /**
     * The default expiry time, some time we may need expire all pages by default, for example, in test mode
     * */
    var expires = EXPIRES

    /**
     * The default time to expire
     * */
    var expireAt = EXPIRE_AT

    /**
     * Lazy flush.
     * */
    var lazyFlush = LAZY_FLUSH

    /**
     * Trigger the parse phase or not.
     *
     * Do not parse by default, since there are may ways to trigger a webpage parsing:
     * 1. use session.parse()
     * 2. add a -parse option
     * 3. use a [ai.platon.pulsar.crawl.common.url.ParsableHyperlink]
     * */
    var parse = PARSE

    /**
     * Store webpage content or not.
     *
     * Store webpage content by default.
     * If we are running a public cloud, this option might be changed to false.
     * */
    var storeContent = STORE_CONTENT
    /**
     * If true, still fetch the page even if it is gone.
     * */
    var ignoreFailure = IGNORE_FAILURE

    /**
     * There are several cases to enable jit retry.
     * For example, in a test environment.
     * */
    var nJitRetry = N_JIT_RETRY

    /**
     * The default browser is chrome with pulsar implemented web driver.
     * */
    var browser = BROWSER

    /**
     * Set to be > 0 if we are doing unit test or other test.
     * We will talk more, log more and trace more in test mode.
     * */
    var test = TEST

    /**
     * Reset all the options to default.
     * */
    fun reset() {
        expires = EXPIRES
        expireAt = EXPIRE_AT
        lazyFlush = LAZY_FLUSH
        parse = PARSE
        storeContent = STORE_CONTENT
        ignoreFailure = IGNORE_FAILURE
        nJitRetry = N_JIT_RETRY
        browser = BROWSER
        test = TEST
    }
}