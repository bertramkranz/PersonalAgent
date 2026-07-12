package com.personalagent.bertbot.app

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.longtermmemory.model.MemoryRecord
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.rag.base.TextDocument
import ai.koog.rag.base.storage.SearchStorage
import ai.koog.rag.base.storage.WriteStorage
import ai.koog.rag.base.storage.search.KeywordSearchRequest
import ai.koog.rag.base.storage.search.Score
import ai.koog.rag.base.storage.search.ScoreMetric
import ai.koog.rag.base.storage.search.SearchRequest
import ai.koog.rag.base.storage.search.SearchResult
import ai.koog.rag.base.storage.search.SimilaritySearchRequest
import ai.koog.utils.time.KoogClock
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.personalagent.bertbot.memory.EpisodicMemory
import com.personalagent.bertbot.memory.MemoryEntry
import com.personalagent.bertbot.memory.SemanticMemory
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.exporter.logging.LoggingSpanExporter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlin.math.max
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal data class KoogMemoryIntegration(
    val chatHistoryProvider: ChatHistoryProvider? = null,
    val longTermStorage: KoogScopedLongTermStorage? = null,
) {
    fun buildPromptContext(
        scopeKey: String,
        userMessage: String,
    ): String {
        val normalizedScope = scopeKey.ifBlank { DEFAULT_SCOPE_KEY }
        val sections = mutableListOf<String>()

        chatHistoryProvider?.let { history ->
            val recentHistory = kotlinx.coroutines.runBlocking { history.load(normalizedScope) }
            val rendered =
                recentHistory
                    .takeLast(8)
                    .mapNotNull { message -> renderMessage(message) }
                    .joinToString(separator = "\n")
                    .trim()
            if (rendered.isNotBlank()) {
                sections += "Recent conversation history:\n$rendered"
            }
        }

        longTermStorage?.let { storage ->
            val searchResults =
                kotlinx.coroutines.runBlocking {
                    storage.search(
                        request = SimilaritySearchRequest(queryText = userMessage, limit = 3),
                        namespace = normalizedScope,
                    )
                }
            val rendered =
                searchResults
                    .mapNotNull { result -> result.document.content.trim().takeIf { it.isNotBlank() } }
                    .distinct()
                    .take(3)
                    .joinToString(separator = "\n- ", prefix = "- ")
                    .trim()
            if (rendered.isNotBlank()) {
                sections += "Long-term memory candidates:\n$rendered"
            }
        }

        return sections.joinToString(separator = "\n\n").trim()
    }

    fun recordTurn(
        scopeKey: String,
        userMessage: String,
        assistantResponse: String,
        traceId: String,
    ) {
        chatHistoryProvider?.let { history ->
            val conversationId = scopeKey.ifBlank { DEFAULT_SCOPE_KEY }
            val priorMessages = kotlinx.coroutines.runBlocking { history.load(conversationId) }
            val updatedMessages =
                priorMessages +
                    listOf(
                        Message.User(userMessage, RequestMetaInfo.create(KoogClock.System)),
                        Message.Assistant(assistantResponse, ResponseMetaInfo.create(KoogClock.System)),
                    )
            kotlinx.coroutines.runBlocking { history.store(conversationId, updatedMessages) }
        }

        longTermStorage?.let { storage ->
            val content = "USER: $userMessage\nASSISTANT: $assistantResponse"
            val metadata = mapOf("trace_id" to traceId, "scope_key" to scopeKey)
            kotlinx.coroutines.runBlocking {
                storage.add(
                    documents = listOf(MemoryRecord(content = content, metadata = metadata)),
                    namespace = scopeKey,
                )
            }
        }
    }

    companion object {
        private const val DEFAULT_SCOPE_KEY = "global"

        private fun renderMessage(message: Message): String? {
            val text =
                message.parts
                    .filterIsInstance<ai.koog.prompt.message.MessagePart.Text>()
                    .joinToString(separator = " ") { it.text }
                    .trim()
                    .ifBlank { return null }

            val role =
                when (message) {
                    is Message.System -> "SYSTEM"
                    is Message.User -> "USER"
                    is Message.Assistant -> "ASSISTANT"
                }
            return "$role: $text"
        }
    }
}

internal interface RuntimeTelemetry : AutoCloseable {
    fun startSpan(
        name: String,
        attributes: Map<String, String> = emptyMap(),
    ): Span?

    fun endSpan(
        span: Span?,
        error: Throwable? = null,
    )

    override fun close()
}

internal object NoOpRuntimeTelemetry : RuntimeTelemetry {
    override fun startSpan(
        name: String,
        attributes: Map<String, String>,
    ): Span? = null

    override fun endSpan(
        span: Span?,
        error: Throwable?,
    ) = Unit

    override fun close() = Unit
}

internal object KoogRuntimeIntegrationFactory {
    fun createMemory(
        configuration: KoogFeatureRuntimeConfiguration,
        memoryRuntime: BertBotMemoryRuntime,
    ): KoogMemoryIntegration {
        val chatProvider =
            if (configuration.chatMemoryEnabled) {
                KoogScopedChatHistoryProvider(
                    episodicMemory = memoryRuntime.episodicMemory,
                    windowSize = configuration.chatMemoryWindowSize,
                )
            } else {
                null
            }
        val longTermStorage =
            if (configuration.longTermMemoryEnabled) {
                KoogScopedLongTermStorage(
                    semanticMemory = memoryRuntime.semanticMemory,
                    defaultTopK = configuration.longTermMemoryTopK,
                )
            } else {
                null
            }

        return KoogMemoryIntegration(
            chatHistoryProvider = chatProvider,
            longTermStorage = longTermStorage,
        )
    }

    fun createTelemetry(configuration: KoogFeatureRuntimeConfiguration): RuntimeTelemetry {
        return OpenTelemetryRuntimeTelemetry(configuration)
    }
}

internal class KoogScopedChatHistoryProvider(
    private val episodicMemory: EpisodicMemory,
    private val windowSize: Int,
) : ChatHistoryProvider {
    override suspend fun store(
        conversationId: String,
        messages: List<Message>,
    ) {
        val normalizedConversationId = conversationId.ifBlank { DEFAULT_SCOPE_KEY }
        val normalizedWindow = max(1, windowSize)
        val persisted = messages.takeLast(normalizedWindow).mapNotNull(::toMemoryEntry)

        episodicMemory.withScope(scopeForConversation(normalizedConversationId)) {
            episodicMemory.replaceAll(persisted)
        }
    }

    override suspend fun load(conversationId: String): List<Message> {
        val normalizedConversationId = conversationId.ifBlank { DEFAULT_SCOPE_KEY }
        return episodicMemory.withScope(scopeForConversation(normalizedConversationId)) {
            episodicMemory.entries().mapNotNull(::toMessage)
        }
    }

    private fun toMemoryEntry(message: Message): MemoryEntry? {
        val text = extractText(message)
        if (text.isNullOrBlank()) {
            return null
        }

        return when (message) {
            is Message.System -> MemoryEntry(text = "SYSTEM: $text")
            is Message.User -> MemoryEntry(text = "USER: $text")
            is Message.Assistant -> MemoryEntry(text = "ASSISTANT: $text")
        }
    }

    private fun toMessage(entry: MemoryEntry): Message? {
        val text = entry.text.trim()
        return when {
            text.startsWith("SYSTEM:", ignoreCase = true) ->
                Message.System(text.removePrefix("SYSTEM:").trim(), RequestMetaInfo.create(KoogClock.System))
            text.startsWith("USER:", ignoreCase = true) ->
                Message.User(text.removePrefix("USER:").trim(), RequestMetaInfo.create(KoogClock.System))
            text.startsWith("ASSISTANT:", ignoreCase = true) ->
                Message.Assistant(text.removePrefix("ASSISTANT:").trim(), ResponseMetaInfo.create(KoogClock.System))
            else -> Message.User(text, RequestMetaInfo.create(KoogClock.System))
        }
    }

    private fun extractText(message: Message): String? {
        return message.parts
            .filterIsInstance<ai.koog.prompt.message.MessagePart.Text>()
            .joinToString(separator = " ") { it.text }
            .trim()
            .ifBlank { null }
    }

    private fun scopeForConversation(conversationId: String): String = "koog_chat_${sanitizeScope(conversationId)}"

    private fun sanitizeScope(scope: String): String =
        scope
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120)
            .ifBlank { DEFAULT_SCOPE_KEY }

    private companion object {
        private const val DEFAULT_SCOPE_KEY = "global"
    }
}

internal class KoogScopedLongTermStorage(
    private val semanticMemory: SemanticMemory,
    private val defaultTopK: Int,
    private val gson: Gson = Gson(),
) : SearchStorage<TextDocument, SearchRequest>, WriteStorage<TextDocument> {
    override suspend fun add(
        documents: List<TextDocument>,
        namespace: String?,
    ): List<String> {
        val normalizedNamespace = normalizeNamespace(namespace)
        val ids = mutableListOf<String>()
        semanticMemory.withScope(scopeForNamespace(normalizedNamespace)) {
            documents.forEach { document ->
                val id = ensureDocumentId(document.id)
                val encoded = EncodedLongTermRecord(id = id, content = document.content, metadata = document.metadata)
                semanticMemory.append("$RECORD_PREFIX${gson.toJson(encoded)}")
                ids.add(id)
            }
        }
        return ids
    }

    override suspend fun update(
        documents: Map<String, TextDocument>,
        namespace: String?,
    ): List<String> {
        if (documents.isEmpty()) {
            return emptyList()
        }

        val normalizedNamespace = normalizeNamespace(namespace)
        val updatedIds = mutableListOf<String>()
        semanticMemory.withScope(scopeForNamespace(normalizedNamespace)) {
            val recordsById = semanticMemory.entries().mapNotNull(::decodeRecord).associateBy { it.id }.toMutableMap()
            documents.forEach { (id, document) ->
                recordsById[id] = EncodedLongTermRecord(id = id, content = document.content, metadata = document.metadata)
                updatedIds.add(id)
            }
            val persistedEntries = recordsById.values.map { record -> MemoryEntry(text = "$RECORD_PREFIX${gson.toJson(record)}") }
            semanticMemory.replaceAll(persistedEntries)
        }

        return updatedIds
    }

    override suspend fun search(
        request: SearchRequest,
        namespace: String?,
    ): List<SearchResult<TextDocument>> {
        val normalizedNamespace = normalizeNamespace(namespace)
        val records =
            semanticMemory.withScope(scopeForNamespace(normalizedNamespace)) {
                semanticMemory.entries().mapNotNull(::decodeRecord)
            }

        val scored =
            when (request) {
                is KeywordSearchRequest -> scoreByKeyword(records, request)
                is SimilaritySearchRequest -> scoreBySimilarity(records, request)
                else -> emptyList()
            }

        return scored
            .sortedByDescending { it.score.value }
            .drop(request.offset)
            .take(max(1, request.limit.coerceAtMost(max(1, defaultTopK))))
    }

    private fun scoreByKeyword(
        records: List<EncodedLongTermRecord>,
        request: KeywordSearchRequest,
    ): List<SearchResult<TextDocument>> {
        val needle = request.queryText.lowercase().trim()
        if (needle.isBlank()) {
            return emptyList()
        }
        val minScore = request.minScore ?: 0.0

        return records
            .filter { it.content.lowercase().contains(needle) }
            .map { record ->
                val document: TextDocument = MemoryRecord(content = record.content, id = record.id, metadata = record.metadata)
                SearchResult(
                    document = document,
                    score = Score(1.0, ScoreMetric.COSINE_SIMILARITY),
                )
            }
            .filter { result -> result.score.value >= minScore }
    }

    private fun scoreBySimilarity(
        records: List<EncodedLongTermRecord>,
        request: SimilaritySearchRequest,
    ): List<SearchResult<TextDocument>> {
        val queryTokens = tokenize(request.queryText)
        if (queryTokens.isEmpty()) {
            return emptyList()
        }
        val minScore = request.minScore ?: 0.0

        return records
            .map { record ->
                val recordTokens = tokenize(record.content)
                val intersection = queryTokens.intersect(recordTokens).size.toDouble()
                val union = queryTokens.union(recordTokens).size.toDouble().coerceAtLeast(1.0)
                val similarity = intersection / union
                val document: TextDocument = MemoryRecord(content = record.content, id = record.id, metadata = record.metadata)
                SearchResult(
                    document = document,
                    score = Score(similarity, ScoreMetric.COSINE_SIMILARITY),
                )
            }
            .filter { result -> result.score.value >= minScore }
    }

    private fun decodeRecord(entry: MemoryEntry): EncodedLongTermRecord? {
        val text = entry.text.trim()
        if (!text.startsWith(RECORD_PREFIX)) {
            return null
        }

        val payload = text.removePrefix(RECORD_PREFIX)
        return try {
            gson.fromJson(payload, EncodedLongTermRecord::class.java)
        } catch (_: JsonSyntaxException) {
            null
        }
    }

    private fun tokenize(text: String): Set<String> =
        text
            .lowercase()
            .split(TOKEN_SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

    private fun normalizeNamespace(namespace: String?): String =
        namespace
            ?.trim()
            ?.ifBlank { DEFAULT_SCOPE_KEY }
            ?.let(::sanitizeScope)
            ?: DEFAULT_SCOPE_KEY

    private fun scopeForNamespace(namespace: String): String = "koog_ltm_$namespace"

    private fun sanitizeScope(scope: String): String =
        scope
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120)
            .ifBlank { DEFAULT_SCOPE_KEY }

    @OptIn(ExperimentalUuidApi::class)
    private fun ensureDocumentId(existingId: String?): String = existingId ?: Uuid.random().toString()

    private data class EncodedLongTermRecord(
        val id: String,
        val content: String,
        val metadata: Map<String, Any> = emptyMap(),
    )

    private companion object {
        private const val DEFAULT_SCOPE_KEY = "global"
        private const val RECORD_PREFIX = "KOOG_LTM|"
        private val TOKEN_SPLIT_REGEX = Regex("[^a-z0-9]+")
    }
}

internal class OpenTelemetryRuntimeTelemetry(
    private val configuration: KoogFeatureRuntimeConfiguration,
) : RuntimeTelemetry {
    private val tracerProvider: SdkTracerProvider
    private val telemetrySdk: OpenTelemetrySdk
    private val tracer: Tracer

    init {
        val serviceNameKey = AttributeKey.stringKey("service.name")
        val serviceVersionKey = AttributeKey.stringKey("service.version")
        val resource =
            Resource.getDefault().merge(
                Resource.create(
                    Attributes.of(
                        serviceNameKey,
                        configuration.openTelemetryServiceName,
                        serviceVersionKey,
                        configuration.openTelemetryServiceVersion,
                    ),
                ),
            )

        val tracerProviderBuilder =
            SdkTracerProvider
                .builder()
                .setResource(resource)
                .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()))

        configuration.openTelemetryOtlpEndpoint?.let { endpoint ->
            val exporter = OtlpGrpcSpanExporter.builder().setEndpoint(endpoint).build()
            tracerProviderBuilder.addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
        }

        tracerProvider = tracerProviderBuilder.build()
        telemetrySdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()
        tracer = telemetrySdk.getTracer("com.personalagent.bertbot.koog")

        if (configuration.openTelemetryVerbose) {
            println(
                "Koog OpenTelemetry enabled for service='${configuration.openTelemetryServiceName}' version='${configuration.openTelemetryServiceVersion}'.",
            )
        }
    }

    override fun startSpan(
        name: String,
        attributes: Map<String, String>,
    ): Span {
        val builder = tracer.spanBuilder(name).setSpanKind(SpanKind.INTERNAL)
        attributes.forEach { (key, value) -> builder.setAttribute(key, value) }
        return builder.startSpan()
    }

    override fun endSpan(
        span: Span?,
        error: Throwable?,
    ) {
        if (span == null) {
            return
        }

        if (error != null) {
            span.recordException(error)
            span.setStatus(StatusCode.ERROR, error.message ?: "runtime_error")
        } else {
            span.setStatus(StatusCode.OK)
        }
        span.end()
    }

    override fun close() {
        tracerProvider.close()
    }
}
