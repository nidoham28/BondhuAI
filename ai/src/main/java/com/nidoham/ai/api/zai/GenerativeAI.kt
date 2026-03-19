package com.nidoham.ai.api.zai

import ai.z.openapi.ZaiClient
import ai.z.openapi.service.model.ChatCompletionCreateParams
import ai.z.openapi.service.model.ChatMessage
import ai.z.openapi.service.model.ChatMessageRole
import ai.z.openapi.service.model.ChatThinking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Industrial-grade stateful wrapper for the ZAI Chat API.
 *
 * Manages conversation history and system instructions internally.
 * This class is thread-safe.
 *
 * @property client The underlying ZAI network client.
 * @property config Configuration parameters for the model.
 */
class GenerativeAI private constructor(
    private val client: ZaiClient,
    private val config: Config
) {
    // Thread-safe storage for conversation history
    private val historyRef: AtomicReference<List<ChatMessage>> = AtomicReference(emptyList())

    // Thread-safe storage for system instructions
    private val instructionsRef: AtomicReference<String> = AtomicReference("")

    /**
     * Represents the result of a chat completion request.
     */
    sealed class Result {
        data class Success(val message: ChatMessage) : Result()
        data class ApiError(val code: Int?, val message: String) : Result()
        data class ExceptionError(val exception: Throwable) : Result()
    }

    /**
     * Replaces the active conversation history.
     * Thread-safe: Can be called from any thread.
     *
     * @param history The new list of messages representing the conversation context.
     */
    fun setHistory(history: List<ChatMessage>) {
        historyRef.set(history.toList()) // Defensive copy
    }

    /**
     * Sets the system-level prompt for the conversation.
     * Thread-safe: Can be called from any thread.
     *
     * @param instructions The system prompt text.
     */
    fun setSystemInstructions(instructions: String) {
        instructionsRef.set(instructions)
    }

    /**
     * Sends the user message to the model using the current history and instructions.
     *
     * @param userMessage The user's input text.
     * @return [Result] containing either the successful message or an error.
     */
    suspend fun sendMessage(userMessage: String): Result = withContext(Dispatchers.IO) {
        try {
            // Capture current state atomically
            val currentHistory = historyRef.get()
            val currentInstructions = instructionsRef.get()

            val userMsg = ChatMessage.builder()
                .role(ChatMessageRole.USER.value())
                .content(userMessage)
                .build()

            val messages = buildList {
                if (currentInstructions.isNotEmpty()) {
                    add(
                        ChatMessage.builder()
                            .role(ChatMessageRole.SYSTEM.value())
                            .content(currentInstructions)
                            .build()
                    )
                }
                addAll(currentHistory)
                add(userMsg)
            }

            val request = ChatCompletionCreateParams.builder()
                .model(config.modelName)
                .messages(messages)
                .apply {
                    if (config.enableThinking) {
                        thinking(ChatThinking.builder().type("enabled").build())
                    }
                }
                .maxTokens(config.maxTokens)
                .temperature(config.temperature)
                .build()

            val response = client.chat().createChatCompletion(request)

            if (response.isSuccess) {
                val choice = response.data.choices.firstOrNull()
                if (choice != null) {
                    Result.Success(choice.message)
                } else {
                    Result.ApiError(null, "No choices returned in successful response")
                }
            } else {
                Result.ApiError(response.code, response.msg ?: "Unknown API Error")
            }
        } catch (e: Exception) {
            Result.ExceptionError(e)
        }
    }

    /**
     * Configuration data class for model parameters.
     */
    data class Config(
        val modelName: String = "glm-4.5-flash",
        val maxTokens: Int = 1024,
        val temperature: Float = 1.0f,
        val enableThinking: Boolean = true
    )

    companion object {
        /**
         * Creates a GenerativeAI instance with an automatically configured client.
         */
        fun create(apiKey: String, config: Config = Config()): GenerativeAI {
            require(apiKey.isNotBlank()) { "API Key cannot be blank." }
            val client = ZaiClient.builder()
                .ofZAI()
                .apiKey(apiKey)
                .build()
            return GenerativeAI(client, config)
        }

        /**
         * Creates a GenerativeAI instance with an externally provided client (Dependency Injection).
         */
        fun create(client: ZaiClient, config: Config = Config()): GenerativeAI {
            return GenerativeAI(client, config)
        }

        // ***********************************************************
        // Helper Methods
        // ***********************************************************

        fun toContent(message: ChatMessage): String =
            message.content?.toString()?.trim() ?: "No response"

        fun toThought(message: ChatMessage): String =
            message.reasoningContent?.trim() ?: "No response"

        fun filterContent(content: String): String =
            content.removePrefix("AI Response:").trimStart()

        fun toConversation(message: ChatMessage): String {
            val thought = toThought(message)
            val content = filterContent(toContent(message))
            return "**$thought**: $content"
        }
    }
}