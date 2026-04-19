
package ai.platon.pulsar.skeleton.crawl.protocol

class ProtocolNotFound(val url: String, message: String = "Protocol not found | $url"): ProtocolException(message)
