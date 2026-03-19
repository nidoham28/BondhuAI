package com.nidoham.ai.domain

/**
 *
 */

enum class MessageRole(val value: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system");

    companion object {
        fun fromValue(value: String): MessageRole = entries.find { it.value == value } ?: USER
    }
}