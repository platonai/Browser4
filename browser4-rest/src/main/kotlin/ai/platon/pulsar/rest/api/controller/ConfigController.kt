package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.context.AgenticContext
import ai.platon.pulsar.agentic.inference.RuntimeConfigRegistry
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Unified runtime configuration interface.
 *
 * One REST surface for every server-side config key that supports runtime
 * overrides (see [RuntimeConfigRegistry.KEY_DEFS]):
 *
 * - `GET    /api/config`             — list all supported keys with their
 *   configured / override / effective values
 * - `GET    /api/config/{key}`       — inspect one key
 * - `PUT    /api/config/{key}?value=…` — set a runtime override, effective
 *   immediately (no restart); the way for the operator to allow a halted
 *   task to continue
 * - `DELETE /api/config/{key}`       — clear the override, falling back to
 *   configuration file values
 *
 * Overrides are runtime-only — lost on server restart. Only whitelisted
 * keys are accepted; unknown keys return 404 and invalid values return 400.
 */
@RestController
@CrossOrigin
@RequestMapping("api/config")
class ConfigController(
    val agenticContext: AgenticContext
) {

    /** List every supported key with its current state. */
    @GetMapping
    fun list(): Map<String, Any?> = mapOf(
        "keys" to RuntimeConfigRegistry.KEY_DEFS.map { describe(it.key) },
        "note" to "Runtime overrides are in-memory only and are cleared on server restart"
    )

    /** Inspect one configuration key. */
    @GetMapping("{key}")
    fun get(@PathVariable key: String): ResponseEntity<Map<String, Any?>> {
        if (!RuntimeConfigRegistry.isSupported(key)) {
            return unknownKey(key)
        }
        return ResponseEntity.ok(describe(key))
    }

    /**
     * Set a runtime override for [key]. The value is validated and
     * normalized by the key's [RuntimeConfigRegistry.KeyDef] before being
     * stored; effective immediately for all consumers.
     */
    @PutMapping("{key}")
    fun set(
        @PathVariable key: String,
        @RequestParam value: String,
    ): ResponseEntity<Map<String, Any?>> {
        if (!RuntimeConfigRegistry.isSupported(key)) {
            return unknownKey(key)
        }
        return try {
            RuntimeConfigRegistry.setOverride(key, value)
            ResponseEntity.ok(describe(key) + ("message" to "Runtime override set; effective immediately"))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                mapOf(
                    "key" to key,
                    "error" to (e.message ?: "Invalid value"),
                    "hint" to "Use a non-negative integer (e.g. 800000), 0, or 'unlimited'"
                )
            )
        }
    }

    /** Clear the runtime override for [key], falling back to configuration values. */
    @DeleteMapping("{key}")
    fun delete(@PathVariable key: String): ResponseEntity<Map<String, Any?>> {
        if (!RuntimeConfigRegistry.isSupported(key)) {
            return unknownKey(key)
        }
        RuntimeConfigRegistry.clearOverride(key)
        return ResponseEntity.ok(describe(key) + ("message" to "Runtime override cleared; using configuration values"))
    }

    private fun describe(key: String): Map<String, Any?> {
        val def = RuntimeConfigRegistry.keyDef(key)!!
        val configured = agenticContext.configuration.get(key)?.trim()?.ifEmpty { null }
        val override = RuntimeConfigRegistry.getOverride(key)
        val effective = override ?: configured ?: def.defaultValue
        return mapOf(
            "key" to key,
            "description" to def.description,
            "configured" to configured,
            "default" to def.defaultValue,
            "override" to override,
            "effective" to effective,
            "unlimited" to (effective.toLongOrNull()?.let { it <= 0 } == true)
        )
    }

    private fun unknownKey(key: String): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            mapOf(
                "key" to key,
                "error" to "Unknown or non-overridable config key '$key'",
                "supportedKeys" to RuntimeConfigRegistry.supportedKeys()
            )
        )
}
