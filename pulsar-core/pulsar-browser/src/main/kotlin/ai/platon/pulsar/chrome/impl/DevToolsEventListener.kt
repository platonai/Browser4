package ai.platon.pulsar.chrome.impl

import ai.platon.cdt.kt.protocol.support.types.EventHandler
import ai.platon.cdt.kt.protocol.support.types.EventListener
import ai.platon.pulsar.chrome.RemoteDevTools

class DevToolsEventListener(
    val key: String,
    val handler: EventHandler<Any>,
    val paramType: Class<*>,
    private val devTools: RemoteDevTools
): EventListener, Comparable<DevToolsEventListener> {
    override fun off() {
        unsubscribe()
    }

    override fun unsubscribe() {
        devTools.removeEventListener(this)
    }

    override fun compareTo(other: DevToolsEventListener): Int {
        return this.key.compareTo(other.key)
    }
}
