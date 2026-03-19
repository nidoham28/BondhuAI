package com.nidoham.ai

import ai.z.openapi.service.model.ChatMessage
import ai.z.openapi.service.model.ChatMessageRole
import com.nidoham.ai.api.zai.GenerativeAI

/**
 * Stateful wrapper around [GenerativeAI] that manages conversation history
 * and syncs it to the delegate before each request.
 *
 * Thread-safe via an explicit lock on [history].
 */
class GenerativeAIWrapper(
    provider: Provider,
    apiKey: String,
    modelName: String = "glm-4.5-flash",
) {
    enum class Provider { GLM, Unknown }

    private val supported = provider == Provider.GLM

    private val delegate: GenerativeAI = GenerativeAI.create(
        apiKey = apiKey,
        config = GenerativeAI.Config(modelName = modelName)
    )

    private val lock = Any()
    private val history: MutableList<ChatMessage> = mutableListOf()

    /**
     * Sends a message if the provider is [Provider.GLM].
     * Returns [Result.failure] with [UnsupportedOperationException] for all other providers.
     */
    suspend fun sendMessage(userMessage: String): Result<ChatMessage> {
        if (!supported) {
            return Result.failure(UnsupportedOperationException("Provider is not supported yet."))
        }

        // Snapshot history atomically before handing off to the delegate
        val snapshot = synchronized(lock) { history.toList() }
        delegate.setHistory(snapshot)

        return when (val result = delegate.sendMessage(userMessage)) {
            is GenerativeAI.Result.Success -> {
                val userMsg = ChatMessage.builder()
                    .role(ChatMessageRole.USER.value())
                    .content(userMessage)
                    .build()
                val assistantMsg = ChatMessage.builder()
                    .role(ChatMessageRole.ASSISTANT.value())
                    .content(GenerativeAI.toContent(result.message))
                    .build()
                synchronized(lock) {
                    history.add(userMsg)
                    history.add(assistantMsg)
                }
                Result.success(result.message)
            }
            is GenerativeAI.Result.ApiError ->
                Result.failure(Exception("API Error ${result.code}: ${result.message}"))
            is GenerativeAI.Result.ExceptionError ->
                Result.failure(result.exception)
        }
    }

    /** Replaces the entire conversation history. */
    fun setHistory(newHistory: List<ChatMessage>) {
        synchronized(lock) {
            history.clear()
            history.addAll(newHistory)
        }
    }

    /** Returns a snapshot of the current conversation history. */
    fun getHistory(): List<ChatMessage> = synchronized(lock) { history.toList() }

    /** Clears the conversation history. */
    fun resetContext() = synchronized(lock) { history.clear() }
}