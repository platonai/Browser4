package ai.platon.pulsar.agentic.inference

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage

/**
 * Converts between Browser4's [SimpleMessage]/[AgentMessageList] and
 * LangChain4j's [ChatMessage] hierarchy.
 */

/**
 * Map a [SimpleMessage] role to the corresponding LangChain4j [ChatMessage].
 */
fun SimpleMessage.toChatMessage(): ChatMessage {
    return when (role.lowercase()) {
        "system" -> SystemMessage.from(content)
        "user" -> {
            val builder = UserMessage.builder()
            builder.addContent(TextContent.from(content))
            if (!name.isNullOrBlank()) builder.name(name)
            builder.build()
        }
        "assistant" -> AiMessage.from(content)
        "tool" -> {
            val id = toolCallId ?: ""
            val tn = toolName ?: "tool"
            ToolExecutionResultMessage.from(id, tn, content)
        }
        else -> UserMessage.from(content)
    }
}

/**
 * Convert an entire [AgentMessageList] into a LangChain4j message list.
 *
 * Blank user/system/tool messages are dropped: LangChain4j rejects blank text on
 * those roles ("text cannot be null or blank"), which used to crash the whole
 * generation step on fresh tasks whose history rendered to an empty string.
 */
fun AgentMessageList.toChatMessages(): List<ChatMessage> {
    return messages
        .filterNot {
            it.role.lowercase() in BLANK_REJECTED_ROLES && it.content.isBlank()
        }
        .map { it.toChatMessage() }
}

/** Roles whose LangChain4j constructors reject blank text. */
private val BLANK_REJECTED_ROLES = setOf("user", "system", "tool")

/**
 * Reverse: [ChatMessage] → [SimpleMessage].
 */
fun ChatMessage.toSimpleMessage(): SimpleMessage {
    return when (this) {
        is SystemMessage -> SimpleMessage("system", text())
        is UserMessage -> SimpleMessage("user", singleText() ?: "", name())
        is AiMessage -> SimpleMessage(
            "assistant",
            text() ?: toolExecutionRequests()?.joinToString("\n") {
                "${it.name()}(${it.arguments()})"
            } ?: ""
        )
        is ToolExecutionResultMessage -> SimpleMessage(
            "tool", text(), toolName(), id(), toolName()
        )
        else -> SimpleMessage("unknown", toString())
    }
}

/**
 * Convert a list of [ChatMessage]s back to an [AgentMessageList].
 */
fun List<ChatMessage>.toAgentMessageList(): AgentMessageList {
    val list = AgentMessageList()
    for (msg in this) {
        list.addLast(msg.toSimpleMessage())
    }
    return list
}

/**
 * Collapse messages of a specific role to a newline-joined string,
 * replicating the legacy `joinToString("\n")` pattern.
 */
fun List<ChatMessage>.collapseToLegacyString(role: String): String {
    return when (role.lowercase()) {
        "system" -> this.filter { it is SystemMessage }
            .joinToString("\n") { (it as SystemMessage).text() }
        "user" -> this.filter { it is UserMessage }
            .joinToString("\n") { (it as UserMessage).singleText() ?: "" }
        "assistant" -> this.filter { it is AiMessage }.joinToString("\n") { msg ->
            val ai = msg as AiMessage
            ai.text() ?: ai.toolExecutionRequests()?.joinToString("\n") {
                "${it.name()}(${it.arguments()})"
            } ?: ""
        }
        else -> joinToString("\n") { it.toString() }
    }
}
