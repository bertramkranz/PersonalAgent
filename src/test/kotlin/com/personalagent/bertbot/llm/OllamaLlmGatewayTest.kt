package com.personalagent.bertbot.llm

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OllamaLlmGatewayTest {
    @Test
    fun `gateway parses message content from chat response`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/chat") { exchange ->
            val body = """{"message":{"role":"assistant","content":"hello from ollama"}}"""
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val gateway = OllamaLlmGateway(baseUrl = "http://127.0.0.1:${server.address.port}", model = "llama3.1")
            val response = gateway.complete(systemPrompt = "system", userPrompt = "user")

            assertEquals("hello from ollama", response)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `gateway throws clear error on non-2xx response`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/chat") { exchange ->
            val body = "model unavailable"
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(503, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val gateway = OllamaLlmGateway(baseUrl = "http://127.0.0.1:${server.address.port}", model = "missing-model")

            val error =
                assertFailsWith<IllegalStateException> {
                    gateway.complete(systemPrompt = "system", userPrompt = "user")
                }

            assertTrue(error.message?.contains("HTTP 503") == true)
        } finally {
            server.stop(0)
        }
    }
}
