package com.nidoham.ai.domain

data class Message(
    val content: String,
    val role: String = MessageRole.USER.value,
    val timestamp: Long = System.currentTimeMillis()
)