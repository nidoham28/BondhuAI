package com.nidoham.ai.api.zai

import ai.z.openapi.ZaiClient
import ai.z.openapi.service.model.ChatCompletionCreateParams
import ai.z.openapi.service.model.ChatMessage
import ai.z.openapi.service.model.ChatMessageRole
import ai.z.openapi.service.model.ChatThinking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pure API wrapper around the ZAI chat endpoint.
 *
 * This class holds no conversation state of its own. The caller is
 * responsible for maintaining history and injecting it before each turn
 * via [setHistory]. [sendMessage] will never mutate the injected list.
 *
 * @param modelName The model identifier to use for completions.
 * @param apiKey    API key for the ZAI client.
 */
class GenerativeAI(
    private val modelName: String = "glm-4.5-flash",
    private val apiKey: String,
) {

    private val client = ZaiClient.builder().ofZAI().apiKey(apiKey).build()

    private var history: List<ChatMessage> = emptyList()
    private var instructions: String = ""

    /**
     * Replaces the active history snapshot used for the next request.
     *
     * The caller is expected to call this before every [sendMessage] turn,
     * passing in their own up-to-date list. A defensive copy is taken so
     * the caller's list and the internal snapshot are fully independent.
     *
     * @param history The conversation history to use for the next request.
     */
    fun setHistory(history: List<ChatMessage>) {
        this.history = history.toList()
    }

    /** Overrides the system-level prompt prepended to every request. */
    fun setSystemInstructions(instructions: String) {
        this.instructions = instructions
    }

    /**
     * Sends [userMessage] to the model and returns the assistant's reply.
     *
     * The injected [history] is used as-is to build the request. No messages
     * are appended internally — the caller must update their own list after
     * receiving a successful result.
     *
     * @param userMessage The user's input text.
     * @return [Result.success] wrapping the assistant [ChatMessage], or
     *         [Result.failure] with the underlying exception.
     */
    suspend fun sendMessage(userMessage: String): Result<ChatMessage> =
        withContext(Dispatchers.IO) {
            try {
                val userMsg = ChatMessage.builder()
                    .role(ChatMessageRole.USER.value())
                    .content(userMessage)
                    .build()

                val messages = buildList {
                    if (instructions.isNotEmpty()) {
                        add(
                            ChatMessage.builder()
                                .role(ChatMessageRole.SYSTEM.value())
                                .content(instructions)
                                .build()
                        )
                    }
                    addAll(history)
                    add(userMsg)
                }

                val request = ChatCompletionCreateParams.builder()
                    .model(modelName)
                    .messages(messages)
                    .thinking(ChatThinking.builder().type("enabled").build())
                    .maxTokens(1024)
                    .temperature(1F)
                    .build()

                val response = client.chat().createChatCompletion(request)

                if (response.isSuccess) {
                    Result.success(response.data.choices[0].message)
                } else {
                    Result.failure(Exception(response.msg))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    companion object {

        fun toContent(message: ChatMessage): String =
            message.content?.toString() ?: "No response"

        fun toThought(message: ChatMessage): String =
            message.reasoningContent ?: "No response"

        fun filterContent(content: String): String =
            content.removePrefix("AI Response:").trimStart()

        fun toConversation(message: ChatMessage): String =
            "**${toThought(message)}**: ${filterContent(toContent(message))}"
    }
}