package ai.platon.pulsar.examples.sites.topEc.chinese.suning

import ai.platon.pulsar.skeleton.context.PulsarContexts

fun main() {
    val portalUrl = "https://search.suning.com/微单/&zw=0?safp=d488778a.shuma.44811515285.1"
    val args = "-i 1s -ii 5d -ol a[href~=item] -ignoreFailure"

    val session = PulsarContexts.createSession()
    session.loadOutPages(portalUrl, args)
}
