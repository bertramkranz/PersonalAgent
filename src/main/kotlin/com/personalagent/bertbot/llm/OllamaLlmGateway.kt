package com.personalagent.bertbot.llm

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class OllamaLlmGateway(
    baseUrl: String,
    private val model: String,
    private val timeout: Duration = Duration.ofSeconds(120),
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(timeout).build(),
    private val gson: Gson = Gson(),
) : LlmGateway {
    private val endpoint: URI =
        URI.create("${baseUrl.trim().trimEnd('/')}/api/chat")

    init {
        require(model.isNotBlank()) { "model must not be blank" }
        require(timeout.seconds > 0) { "timeout must be positive" }
    }

    override fun complete(
        systemPrompt: String,
        userPrompt: String,
    ): String {
        val payload =
            mapOf(
                "model" to model,
                "stream" to false,
                "messages" to
                    listOf(
                        mapOf("role" to "system", "content" to systemPrompt),
                        mapOf("role" to "user", "content" to userPrompt),
                    ),
            )

        val request =
            HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "Ollama request failed with HTTP ${response.statusCode()}: ${response.body()}"
        }

        return parseCompletionContent(response.body())
    }

    private fun parseCompletionContent(body: String): String {
        val root = JsonParser.parseString(body).asJsonObject
        val message = root.getAsJsonObject("message")
        val messageContent = message?.get("content")?.asString
        if (!messageContent.isNullOrBlank()) {
            return messageContent
        }

        val generateContent = root.get("response")?.asString
        return generateContent ?: ""
    }
}
