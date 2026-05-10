package ai.platon.pulsar.skeleton.workflow.fetch.privacy

enum class CloseStrategy {
    ASAP,
    // it might be a bad idea to close lazily, it is experimental.
    LAZY
}
