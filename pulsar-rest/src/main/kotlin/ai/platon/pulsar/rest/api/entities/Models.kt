package ai.platon.pulsar.rest.api.entities

/**
 * W3 resources
 * */
data class W3DocumentRequest(
    var url: String,
    val args: String? = null,
)
