package ai.platon.pulsar.skeleton.workflow.protocol

class ProtocolNotFound(val url: String, message: String = "Protocol not found | $url"): ProtocolException(message)
