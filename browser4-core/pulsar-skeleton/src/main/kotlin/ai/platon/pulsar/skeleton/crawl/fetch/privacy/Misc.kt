package ai.platon.pulsar.skeleton.crawl.fetch.privacy

enum class CloseStrategy {
    ASAP,
    // it might be a bad idea to close lazily, it is experimental.
    LAZY
}
