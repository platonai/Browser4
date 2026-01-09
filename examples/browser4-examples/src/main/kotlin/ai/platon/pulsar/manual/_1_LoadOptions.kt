package ai.platon.pulsar.manual

import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.context.PulsarContexts

/**
 * Demonstrates the usage of load options.
 * */
fun main() {
    // Use the default browser which has an isolated profile.
    // You can also try other browsers, such as system default, prototype, sequential, temporary, etc.
    PulsarSettings.withDefaultBrowser()

    // Create a pulsar session
    val session = PulsarContexts.createSession()
    // The main url we are playing with
    val url = PRODUCT_DETAIL_URL

    // Load a page, or fetch it if the expiry time exceeds.
    //
    // Option `-expires` specifies the expiry time and has a short form `-i`.
    //
    // The expiry time support both ISO-8601 standard and hadoop time duration format:
    // 1. ISO-8601 standard : PnDTnHnMn.nS
    // 2. Hadoop time duration format : Valid units are : ns, us, ms, s, m, h, d.
    var page = session.load(url, "-expires 10s")
    page = session.load(url, "-i 10s")

    // Add option `-ignoreFailure` to force re-fetch ignoring all failures even if `fetchRetries` exceeds the maximal.
    page = session.load(url, "-ignoreFailure -expires 0s")

    // Add option `-refresh` to force re-fetch ignoring all failures and set `fetchRetries` to be 0,
    // `-refresh` = `-ignoreFailure -expires 0s` and `page.fetchRetires = 0`.
    page = session.load(url, "-refresh")

    // Option `-requireSize` to specifies the minimal page size, the page should be re-fetch if the
    // last page size is smaller than that.
    page = session.load(url, "-requireSize 300000")

    // Option `-requireImages` specifies the minimal image count, the page should be re-fetch if the image count of the
    // last fetched page is smaller than that.
    page = session.load(url, "-requireImages 10")

    // Option `-requireAnchors` specifies the minimal anchor count, the page should be re-fetch if
    // the anchor count of the last fetched page is smaller than that.
    page = session.load(url, "-requireAnchors 100")

    // If the deadline is exceeded, the task should be abandoned as soon as possible.
    page = session.load(url, "-deadline 2022-04-15T18:36:54.941Z")

    // Add option `-parse` to activate the parsing subsystem.
    page = session.load(url, "-parse")

    // Option `-storeContent` tells the system to save the page content to the storage.
    page = session.load(url, "-storeContent")

    // Option `-nMaxRetry` specifies the maximal number of retries in the crawl loop, and if it's still failed
    // after this number, the page will be marked as `Gone`. A retry will be triggered when a RETRY(1601) status code
    // is returned.
    page = session.load(url, "-nMaxRetry 3")

    // Option `-nJitRetry` specifies the maximal number of retries for the load phase, which will be triggered
    // when a RETRY(1601) status is returned.
    page = session.load(url, "-nJitRetry 2")

    // Load or fetch the portal page, and then load or fetch the out links selected by `-outLink`.
    //
    // 1. `-expires` specifies the expiry time of item pages and has a short form `-ii`.
    // 2. `-outLink` specifies the cssSelector for links in the portal page to load.
    // 3. `-topLinks` specifies the maximal number of links selected by `-outLink`.
    //
    // Fetch conditions:
    // 1. `-itemExpires` specifies the expiry time of item pages and has a short form `-ii`.
    // 2. `-itemRequireSize` specifies the minimal page size.
    // 3. `-itemRequireImages` specifies the minimal number of images in the page.
    // 4. `-itemRequireAnchors` specifies the minimal number of anchors in the page.
    var pages = session.loadOutPages(url, "-expires 10s" +
            " -itemExpires 7d" +
            " -outLink a[href~=item]" +
            " -topLinks 10" +
            " -itemExpires 1d" +
            " -itemRequireSize 600000" +
            " -itemRequireImages 5" +
            " -itemRequireAnchors 50"
    )

    // Wait until all tasks are done.
    session.context.await()
}
