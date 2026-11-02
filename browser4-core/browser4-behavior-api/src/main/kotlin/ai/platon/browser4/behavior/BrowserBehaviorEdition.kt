package ai.platon.browser4.behavior

/**
 * The edition a [ai.platon.browser4.behavior.stealth.BrowserStealthProvider] or
 * [ai.platon.browser4.behavior.humanization.InteractionHumanizer] belongs to.
 *
 * Browser4 ships the [DEFAULT] (community) implementation in `browser4-behavior-default`.
 * A professional or custom edition is a separate module that depends on
 * `browser4-behavior-api`, implements the same SPI and declares its own edition string, so
 * every provider can report which product tier is actually active.
 *
 * The edition is informational only: provider *selection* is by provider id or by priority
 * (see [BrowserBehavior]), never by edition.
 */
object BrowserBehaviorEdition {
    /** The community/default implementation shipped with Browser4. */
    const val DEFAULT: String = "default"

    /** A professional edition implementation, provided by a separate module. */
    const val PROFESSIONAL: String = "professional"

    /** A customer-specific implementation, provided by a separate module. */
    const val CUSTOM: String = "custom"
}
