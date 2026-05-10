package ai.platon.pulsar.agentic.inference

data class SimpleMessage(
    val role: String,
    val content: String,
    val name: String? = null,
) {
    enum class Role {
        USER, SYSTEM
    }

    override fun toString() = content
}

class AgentMessageList(
    val messages: MutableList<SimpleMessage> = mutableListOf()
) {
    val instruction get() = find("instruction") ?: find("user_request")

    fun addSystem(message: String, name: String? = null) {
        addLast("system", message, name)
    }

    fun addUser(message: String, name: String? = null) {
        addLast("user", message, name)
    }

    fun addFirst(role: String, message: String, name: String? = null) {
        val msg = SimpleMessage(role, message, name)
        messages.add(0, msg)
    }

    fun addFirst(message: SimpleMessage) {
        val msg = SimpleMessage(message.role, message.content, message.name)
        messages.add(0, msg)
    }

    fun addLast(role: String, content: String, name: String? = null) {
        val msg = SimpleMessage(role, content, name)
        messages.add(msg)
    }

    fun addLastIfAbsent(role: String, content: String, name: String) {
        if (find(name) == null) {
            val msg = SimpleMessage(role, content, name)
            messages.add(msg)
        }
    }

    fun addLast(message: SimpleMessage) {
        val msg = SimpleMessage(message.role, message.content, message.name)
        messages.add(msg)
    }

    fun find(key: String): SimpleMessage? {
        return messages.find { it.name == key }
    }

    fun exists(key: String): Boolean {
        return find(key) != null
    }

    fun systemMessages() = messages.filter { it.role == "system" }

    fun userMessages() = messages.filter { it.role == "user" }

    override fun toString(): String {
        return messages.joinToString("\n")
    }
}
