package com.nidoham.ai

import ai.z.openapi.service.model.ChatMessage
import ai.z.openapi.service.model.ChatMessageRole
import com.nidoham.ai.api.zai.GenerativeAI

class GenerativeAIWrapper(
    private val provider: Provider,
    private val apiKey: String,
    private val modelName: String = "glm-4.5-flash",
) {

    enum class Provider { GLM, Unknown }

    private val conversationHistory: MutableList<ChatMessage> = mutableListOf()

    /**
     * Seeds the stateless delegate with accumulated history, dispatches [userMessage],
     * and on success appends both turns to local history.
     *
     * Returns [Result.failure] when no provider is mapped.
     */
    suspend fun sendMessage(userMessage: String): Result<String> {
        if (provider == Provider.GLM) {
            val delegate = GenerativeAI(modelName = modelName, apiKey = apiKey)
            delegate.setHistory(conversationHistory)
            val result = delegate.sendMessage(userMessage)
            result.onSuccess { response ->
                conversationHistory.add(
                    ChatMessage.builder().role(ChatMessageRole.USER.value()).content(userMessage).build()
                )
                conversationHistory.add(
                    ChatMessage.builder().role(ChatMessageRole.ASSISTANT.value()).content(response).build()
                )
            }
            return result
        } else {
            return Result.failure(UnsupportedOperationException("Provider $provider is not supported."))
        }
    }

    /**
     * Replaces the conversation history.
     * Useful for restoring a persisted session or pre-seeding a system prompt.
     */
    fun setHistory(history: List<ChatMessage>) {
        if (provider == Provider.GLM) {
            conversationHistory.clear()
            conversationHistory.addAll(history)
        } else {
            // No-op: provider not mapped.
        }
    }

    /** Returns a read-only snapshot of the current conversation history. */
    fun getHistory(): List<ChatMessage> {
        return if (provider == Provider.GLM) {
            conversationHistory.toList()
        } else {
            emptyList()
        }
    }

    /** Clears the conversation history. */
    fun resetContext() {
        if (provider == Provider.GLM) {
            conversationHistory.clear()
        } else {
            // No-op: provider not mapped.
        }
    }
}