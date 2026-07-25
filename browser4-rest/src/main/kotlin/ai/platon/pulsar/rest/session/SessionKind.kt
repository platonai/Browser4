package ai.platon.pulsar.rest.session

/**
 * Explicit taxonomy of how a Browser4 session relates to its browser.
 *
 * Previously this was implicit — derived from [ai.platon.pulsar.rest.session.ManagedSession]
 * creation path and the `is_attached` / `attach_type` flags on the CLI side.
 * Making it explicit lets lifecycle decisions (close vs detach, health-check recreation)
 * be driven by the kind rather than by scattered boolean checks.
 */
enum class SessionKind {
    /** Browser4 launched and owns the browser process lifecycle.  Created by `open`. */
    BROWSER4_LAUNCHED,

    /** Attached to an externally-managed browser via CDP remote debugging port.
     *  Browser4 does NOT own the browser.  Created by `attach --cdp`. */
    CDP_ATTACHED,

    /** Attached via the Browser4 Chrome Extension WebSocket relay.
     *  Browser4 does NOT own the browser.  Created by `attach --extension`. */
    EXTENSION_ATTACHED,

    /** Shared swarm session for parallel scraping workloads.
     *  Owns its browser pool.  Created by `swarm create`. */
    SWARM;

    /** Whether this kind of session owns its browser lifecycle.
     *  Only [BROWSER4_LAUNCHED] and [SWARM] sessions own their browsers;
     *  attached sessions ([CDP_ATTACHED], [EXTENSION_ATTACHED]) reference external browsers. */
    val ownsBrowser: Boolean
        get() = this == BROWSER4_LAUNCHED || this == SWARM
}
