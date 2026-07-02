package ai.platon.pulsar.agentic.model

/**
 * Marker interface for tool result values that should be stored directly in [TcEvaluate.value]
 * without being wrapped into a description map.
 *
 * Types implementing this interface declare themselves safe for direct storage (simple data
 * carriers with no complex object graphs that would cause serialization issues).
 *
 * @see TcEvaluate
 */
interface DirectValue
