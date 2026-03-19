package com.nidoham.ai

import ai.z.openapi.service.model.ChatMessage
import ai.z.openapi.service.model.ChatMessageRole
import com.nidoham.ai.api.zai.GenerativeAI
import java.util.Collections

class GenerativeAIWrapper(
    private val provider: Provider,
    private val apiKey: String,
    private val modelName: String = "glm-4.5-flash",
) {

    enum class Provider { GLM, Unknown }

    // Optimized: Initialize delegate once to reuse connection/resources
    private val delegate: GenerativeAI = GenerativeAI.create(
        apiKey = apiKey,
        config = GenerativeAI.Config(modelName = modelName)
    )

    // Thread-safe history list
    private val conversationHistory: MutableList<ChatMessage> =
        Collections.synchronizedList(mutableListOf())

    /**
     * Sends message if Provider is GLM.
     * Shows "Coming Soon" Toast and returns failure otherwise.
     */
    suspend fun sendMessage(userMessage: String): Result<ChatMessage> {
        if (provider == Provider.GLM) {
            // 1. Sync history to the delegate
            delegate.setHistory(conversationHistory.toList())

            // 2. Execute request
            val result = delegate.sendMessage(userMessage)

            // 3. Handle result
            return when (result) {
                is GenerativeAI.Result.Success -> {
                    // Add User message
                    conversationHistory.add(
                        ChatMessage.builder()
                            .role(ChatMessageRole.USER.value())
                            .content(userMessage)
                            .build()
                    )
                    // Add Assistant message
                    conversationHistory.add(
                        ChatMessage.builder()
                            .role(ChatMessageRole.ASSISTANT.value())
                            .content(result.message.content) // Extract content safely
                            .build()
                    )
                    Result.success(result.message)
                }
                is GenerativeAI.Result.ApiError -> {
                    Result.failure(Exception("API Error ${result.code}: ${result.message}"))
                }
                is GenerativeAI.Result.ExceptionError -> {
                    Result.failure(result.exception)
                }
            }
        } else {
            // Handle non-GLM providers
            return Result.failure(UnsupportedOperationException("Provider $provider is not supported yet."))
        }
    }

    fun setHistory(history: List<ChatMessage>) {
        synchronized(conversationHistory) {
            conversationHistory.clear()
            conversationHistory.addAll(history)
        }
    }

    fun getHistory(): List<ChatMessage> {
        return synchronized(conversationHistory) {
            conversationHistory.toList()
        }
    }

    fun resetContext() {
        synchronized(conversationHistory) {
            conversationHistory.clear()
        }
    }
}