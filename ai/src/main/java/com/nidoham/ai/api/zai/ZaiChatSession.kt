package com.nidoham.ai.api.zai

import ai.z.openapi.ZaiClient
import ai.z.openapi.service.model.ChatCompletionCreateParams
import ai.z.openapi.service.model.ChatMessage
import ai.z.openapi.service.model.ChatMessageRole
import ai.z.openapi.service.model.ChatThinking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages a stateful conversation session with the ZAI API.
 *
 * This class handles history management, system instructions, and thread-safe interaction
 * with the underlying AI model.
 *
 * @property client The configured ZAI network client.
 * @property config The model configuration parameters.
 */
class ZaiChatSession private constructor(
    private val client: ZaiClient,
    private val config: ChatConfig
) {
    // Thread-safe storage for conversation history
    private val historyRef: AtomicReference<List<ChatMessage>> = AtomicReference(emptyList())

    // Thread-safe storage for system prompt
    private val systemPromptRef: AtomicReference<String> = AtomicReference("")

    // Mutex to ensure thread-safety during the read-modify-write cycle of a chat turn
    private val conversationLock = Mutex()

    /**
     * Represents the outcome of a chat request.
     */
    sealed class ChatResult {
        data class Success(val message: ChatMessage) : ChatResult()
        data class ApiError(val code: Int?, val message: String) : ChatResult()
        data class ExceptionError(val exception: Throwable) : ChatResult()
    }

    /**
     * Replaces the entire conversation history.
     * Thread-safe: Can be called from any thread.
     *
     * @param history The new list of messages to set as the conversation context.
     */
    fun setHistory(history: List<ChatMessage>) {
        historyRef.set(history.toList())
    }

    /**
     * Clears the current conversation history.
     */
    fun clearHistory() {
        historyRef.set(emptyList())
    }

    /**
     * Sets the system-level instructions (prompt) for the AI.
     * Thread-safe: Can be called from any thread.
     *
     * @param prompt The system instruction text.
     */
    fun setSystemPrompt(prompt: String) {
        systemPromptRef.set(prompt)
    }

    /**
     * Sends a user message to the model and updates the internal history automatically.
     *
     * This operation is atomic; it locks the session to ensure history is updated correctly
     * before the next message is processed.
     *
     * @param userMessage The text input from the user.
     * @return [ChatResult] containing the AI's response or an error.
     */
    suspend fun chat(userMessage: String): ChatResult = withContext(Dispatchers.IO) {
        // Lock to prevent race conditions if chat() is called rapidly from multiple coroutines
        conversationLock.withLock {
            try {
                val currentInstructions = systemPromptRef.get()
                val currentHistory = historyRef.get()

                val userMsg = ChatMessage.builder()
                    .role(ChatMessageRole.USER.value())
                    .content(userMessage)
                    .build()

                // Construct the API request payload
                val requestMessages = buildList {
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
                    .messages(requestMessages)
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
                        val assistantMsg = choice.message

                        // SUCCESS FIX: Update history to maintain conversation context
                        historyRef.set(requestMessages + assistantMsg)

                        ChatResult.Success(assistantMsg)
                    } else {
                        ChatResult.ApiError(null, "No choices returned in successful response")
                    }
                } else {
                    ChatResult.ApiError(response.code, response.msg ?: "Unknown API Error")
                }
            } catch (e: Exception) {
                ChatResult.ExceptionError(e)
            }
        }
    }

    /**
     * Configuration data class for the ZAI model parameters.
     */
    data class ChatConfig(
        val modelName: String = "glm-4.5-flash",
        val maxTokens: Int = 1024,
        val temperature: Float = 1.0f,
        val enableThinking: Boolean = true
    )

    companion object {
        /**
         * Creates a [ZaiChatSession] with an internally managed client.
         *
         * @param config Configuration for the model.
         * @return A new instance of [ZaiChatSession].
         */
        fun create(config: ChatConfig = ChatConfig()): ZaiChatSession {
            val client = ZaiClient.builder()
                .ofZAI()
                .apiKey(API.API_KEYS)
                .build()
            return ZaiChatSession(client, config)
        }

        /**
         * Creates a [ZaiChatSession] with an externally provided client (Dependency Injection).
         *
         * @param client An existing [ZaiClient] instance.
         * @param config Configuration for the model.
         * @return A new instance of [ZaiChatSession].
         */
        fun create(client: ZaiClient, config: ChatConfig = ChatConfig()): ZaiChatSession {
            return ZaiChatSession(client, config)
        }
    }
}

// ***********************************************************
// Extension Functions for Message Handling
// ***********************************************************

/**
 * Extracts the textual content from a ChatMessage safely.
 */
fun ChatMessage.extractContent(): String =
    this.content?.toString()?.trim() ?: "No response"

/**
 * Extracts the reasoning/thought content from a ChatMessage safely.
 */
fun ChatMessage.extractReasoning(): String =
    this.reasoningContent?.trim() ?: "No reasoning provided"

/**
 * Cleans and formats the message content for display.
 */
fun ChatMessage.formatContent(): String =
    extractContent().removePrefix("AI Response:").trimStart()

/**
 * Formats the message into a readable conversation string combining reasoning and content.
 */
fun ChatMessage.toConversationString(): String {
    val thought = extractReasoning()
    val content = formatContent()
    return "**$thought**: $content"
}