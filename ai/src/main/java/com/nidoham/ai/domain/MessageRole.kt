package com.nidoham.ai.domain

enum class MessageRole(val values: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system");

    companion object {
        fun fromModelName(name: String): ModelType? = ModelType.entries.find { it.modelName == name }
    }
}