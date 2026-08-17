package ai.platon.pulsar.skeleton.event

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.skeleton.event.impl.DefaultPageEventHandlers
import ai.platon.pulsar.skeleton.event.impl.PageEventHandlersFactory
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PageEventHandlersFactory].
 *
 * The default handler is now constructed directly (no reflection) so the
 * common path stays GraalVM native-image friendly. These tests guard that
 * behavior: the factory must always return a working [PageEventHandlers],
 * defaulting to [DefaultPageEventHandlers] when no custom class is configured.
 */
class PageEventHandlersFactoryTest {

    private val factory = PageEventHandlersFactory(ImmutableConfig())

    @Test
    @DisplayName("create() returns DefaultPageEventHandlers without custom config")
    fun createReturnsDefaultHandlers() {
        val handlers = factory.create()

        assertInstanceOf(DefaultPageEventHandlers::class.java, handlers)
    }

    @Test
    @DisplayName("create(defaultClassName) returns DefaultPageEventHandlers directly")
    fun createWithDefaultClassNameReturnsDefaultHandlers() {
        val handlers = factory.create(DefaultPageEventHandlers::class.java.name)

        assertInstanceOf(DefaultPageEventHandlers::class.java, handlers)
    }

    @Test
    @DisplayName("create(unknownClassName) falls back to DefaultPageEventHandlers")
    fun createWithUnknownClassNameFallsBackToDefault() {
        val handlers = factory.create("ai.platon.pulsar.skeleton.event.NonExistentPageEventHandlers")

        assertInstanceOf(DefaultPageEventHandlers::class.java, handlers)
    }

    @Test
    @DisplayName("companion create() returns a working DefaultPageEventHandlers")
    fun companionCreateReturnsDefaultHandlers() {
        val handlers = PageEventHandlersFactory.create()

        assertInstanceOf(DefaultPageEventHandlers::class.java, handlers)
    }
}
