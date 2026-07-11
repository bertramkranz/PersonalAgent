package com.personalagent.bertbot.app

import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

internal data class PolymarketHttpResponse(
    val statusCode: Int,
    val body: String,
    val contentType: String?,
    val retryAfter: String?,
)

internal class PolymarketApiClient(
    gammaBaseUrl: String = DEFAULT_GAMMA_BASE_URL,
    clobBaseUrl: String = DEFAULT_CLOB_BASE_URL,
    dataBaseUrl: String = DEFAULT_DATA_BASE_URL,
    private val timeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(timeout).build(),
) {
    private val gammaRoot = normalizeBaseUrl(gammaBaseUrl)
    private val clobRoot = normalizeBaseUrl(clobBaseUrl)
    private val dataRoot = normalizeBaseUrl(dataBaseUrl)

    fun gammaGet(
        path: String,
        queryParameters: Map<String, String?> = emptyMap(),
    ): PolymarketHttpResponse = get(gammaRoot, path, queryParameters)

    fun clobGet(
        path: String,
        queryParameters: Map<String, String?> = emptyMap(),
    ): PolymarketHttpResponse = get(clobRoot, path, queryParameters)

    fun dataGet(
        path: String,
        queryParameters: Map<String, String?> = emptyMap(),
    ): PolymarketHttpResponse = get(dataRoot, path, queryParameters)

    private fun get(
        baseUrl: String,
        path: String,
        queryParameters: Map<String, String?>,
    ): PolymarketHttpResponse {
        val uri = buildUri(baseUrl, path, queryParameters)
        val request =
            HttpRequest
                .newBuilder(uri)
                .header("Accept", "application/json")
                .header("User-Agent", "BertBot-Polymarket-Bridge/1.0")
                .timeout(timeout)
                .GET()
                .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return PolymarketHttpResponse(
            statusCode = response.statusCode(),
            body = response.body(),
            contentType = response.headers().firstValue("content-type").orElse(null),
            retryAfter = response.headers().firstValue("retry-after").orElse(null),
        )
    }

    private fun buildUri(
        baseUrl: String,
        path: String,
        queryParameters: Map<String, String?>,
    ): URI {
        val normalizedPath = if (path.startsWith('/')) path else "/$path"
        val query =
            queryParameters.entries
                .asSequence()
                .filter { (_, value) -> !value.isNullOrBlank() }
                .joinToString(separator = "&") { (key, value) ->
                    "${encodeQueryPart(key)}=${encodeQueryPart(value.orEmpty())}"
                }
        val fullUrl = if (query.isBlank()) "$baseUrl$normalizedPath" else "$baseUrl$normalizedPath?$query"
        return URI.create(fullUrl)
    }

    companion object {
        private const val DEFAULT_GAMMA_BASE_URL = "https://gamma-api.polymarket.com"
        private const val DEFAULT_CLOB_BASE_URL = "https://clob.polymarket.com"
        private const val DEFAULT_DATA_BASE_URL = "https://data-api.polymarket.com"
        private const val DEFAULT_TIMEOUT_SECONDS = 20L

        fun fromEnvironment(environment: Map<String, String> = System.getenv()): PolymarketApiClient {
            val gammaBaseUrl = environment["BERTBOT_POLYMARKET_GAMMA_BASE_URL"] ?: DEFAULT_GAMMA_BASE_URL
            val clobBaseUrl = environment["BERTBOT_POLYMARKET_CLOB_BASE_URL"] ?: DEFAULT_CLOB_BASE_URL
            val dataBaseUrl = environment["BERTBOT_POLYMARKET_DATA_BASE_URL"] ?: DEFAULT_DATA_BASE_URL
            return PolymarketApiClient(
                gammaBaseUrl = gammaBaseUrl,
                clobBaseUrl = clobBaseUrl,
                dataBaseUrl = dataBaseUrl,
            )
        }

        fun formatResponseForTool(
            apiFamily: String,
            operation: String,
            response: PolymarketHttpResponse,
            maxBodyChars: Int = 60_000,
        ): Pair<Boolean, String> {
            val body = response.body.trim()
            val formattedBody =
                if (body.isBlank()) {
                    "(empty response body)"
                } else {
                    prettyPrintIfJson(body).take(maxBodyChars)
                }

            val retryAfterLine = response.retryAfter?.let { "\nretry_after: $it" } ?: ""
            val truncationLine =
                if (body.length > maxBodyChars) {
                    "\n\n[truncated: showing first $maxBodyChars characters]"
                } else {
                    ""
                }

            val header =
                "source=$apiFamily operation=$operation http_status=${response.statusCode}" +
                    " content_type=${response.contentType ?: "unknown"}" +
                    retryAfterLine

            val isError = response.statusCode !in 200..299
            return isError to "$header\n\n$formattedBody$truncationLine"
        }

        private fun prettyPrintIfJson(raw: String): String {
            val parsed = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return raw
            return parsed.toString()
        }

        private fun normalizeBaseUrl(value: String): String = value.trim().trimEnd('/')

        private fun encodeQueryPart(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
    }
}
