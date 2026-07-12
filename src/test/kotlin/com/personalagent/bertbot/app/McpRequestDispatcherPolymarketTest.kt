package com.personalagent.bertbot.app

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpRequestDispatcherPolymarketTest {
    @Test
    fun `polymarket gamma query routes to configured gamma endpoint`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/markets") { exchange ->
            val body = """[{"slug":"btc-above-100k","active":true}]"""
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val client =
                PolymarketApiClient(
                    gammaBaseUrl = "http://127.0.0.1:${server.address.port}",
                    clobBaseUrl = "http://127.0.0.1:${server.address.port}",
                    dataBaseUrl = "http://127.0.0.1:${server.address.port}",
                )
            val dispatcher =
                McpRequestDispatcher(
                    respondToPrompt = { _, _ -> "unused" },
                    polymarketToolRouter = PolymarketToolRouter(client),
                )

            val response =
                dispatcher.handle(
                    """
                    {"jsonrpc":"2.0","id":120,"method":"tools/call","params":{"name":"polymarket_gamma_query","arguments":{"operation":"list_markets","limit":1}}}
                    """.trimIndent(),
                )

            val json = JsonParser.parseString(response).asJsonObject
            val text = json.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
            assertTrue(text.contains("source=gamma operation=list_markets http_status=200"))
            assertTrue(text.contains("btc-above-100k"))
            assertEquals(false, json.getAsJsonObject("result").get("isError").asBoolean)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `polymarket data query reports upstream http errors`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/positions") { exchange ->
            val body = "{\"error\":\"missing user\"}"
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(400, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val client =
                PolymarketApiClient(
                    gammaBaseUrl = "http://127.0.0.1:${server.address.port}",
                    clobBaseUrl = "http://127.0.0.1:${server.address.port}",
                    dataBaseUrl = "http://127.0.0.1:${server.address.port}",
                )
            val dispatcher =
                McpRequestDispatcher(
                    respondToPrompt = { _, _ -> "unused" },
                    polymarketToolRouter = PolymarketToolRouter(client),
                )

            val response =
                dispatcher.handle(
                    """
                    {"jsonrpc":"2.0","id":121,"method":"tools/call","params":{"name":"polymarket_data_query","arguments":{"operation":"get_positions"}}}
                    """.trimIndent(),
                )

            val json = JsonParser.parseString(response).asJsonObject
            val text = json.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
            assertTrue(text.contains("source=data operation=get_positions http_status=400"))
            assertTrue(text.contains("missing user"))
            assertEquals(true, json.getAsJsonObject("result").get("isError").asBoolean)
        } finally {
            server.stop(0)
        }
    }
}
