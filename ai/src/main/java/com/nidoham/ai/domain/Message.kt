package com.nidoham.ai.domain

data class Message(
    val content: String,
    val role: MessageRole = MessageRole.USER,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun create(content: String, roleValue: String): Message {
            val role = MessageRole.fromValue(roleValue) ?: MessageRole.USER
            return Message(content = content, role = role)
        }

        fun assistant(content: String): Message {
            return Message(content = content, role = MessageRole.ASSISTANT)
        }

        fun user(content: String): Message {
            return Message(content = content, role = MessageRole.USER)
        }

        fun system(content: String): Message {
            return Message(content = content, role = MessageRole.SYSTEM)
        }
    }
}
