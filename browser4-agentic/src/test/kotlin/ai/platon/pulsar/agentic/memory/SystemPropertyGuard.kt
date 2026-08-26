package ai.platon.pulsar.agentic.memory

/**
 * Test isolation helper for JVM-global system properties.
 *
 * `MemoryConfig` reads `browser4.agent.memory.*` system properties at class
 * load; tests that need a non-default configuration must snapshot and restore
 * the properties around their block so parallel/serial test runs cannot
 * poison each other.
 */
object SystemPropertyGuard {

    /** Run [block] with the given properties set (null value = cleared), restoring the previous state after. */
    fun <T> withProperties(vararg pairs: Pair<String, String?>, block: () -> T): T {
        val previous = pairs.associate { (key, _) -> key to System.getProperty(key) }
        try {
            pairs.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
            return block()
        } finally {
            previous.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
        }
    }
}
