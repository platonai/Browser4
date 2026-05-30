package ai.platon.pulsar.browser.privacy

enum class CloseStrategy {
    ASAP,
    // it might be a bad idea to close lazily, it is experimental.
    LAZY
}
