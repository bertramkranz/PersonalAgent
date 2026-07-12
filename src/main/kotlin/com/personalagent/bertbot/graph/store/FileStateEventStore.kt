package com.personalagent.bertbot.graph.store

import com.google.gson.JsonSyntaxException
import com.personalagent.bertbot.graph.runtime.StateEvent
import com.personalagent.bertbot.graph.runtime.StateEventStore
import com.personalagent.bertbot.graph.runtime.StateEventType
import com.personalagent.bertbot.graph.runtime.copyForPersistence
import com.personalagent.bertbot.serialization.AgentJsonCodec
import com.personalagent.bertbot.serialization.GsonAgentJsonCodec
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class FileStateEventStore(
    private val file: File,
    private val codec: AgentJsonCodec = GsonAgentJsonCodec(),
) : StateEventStore {
    private val lock = Any()

    override fun append(event: StateEvent) {
        synchronized(lock) {
            val payload = loadPayloadInternal().toMutableList()
            payload.add(PersistedStateEvent.fromDomain(event))
            val persisted = PersistedStateEventEnvelope(events = payload)
            file.parentFile?.mkdirs()
            writeTextAtomically(file, codec.encode(persisted))
        }
    }

    override fun list(scopeKey: String): List<StateEvent> {
        synchronized(lock) {
            return loadPayloadInternal()
                .asSequence()
                .filter { it.scopeKey == scopeKey }
                .sortedWith(compareBy<PersistedStateEvent> { it.createdAtEpochMillis }.thenBy { it.eventId })
                .map { it.toDomain() }
                .toList()
        }
    }

    private fun loadPayloadInternal(): List<PersistedStateEvent> {
        if (!file.exists()) {
            return emptyList()
        }

        val content = file.readText()
        if (content.isBlank()) {
            return emptyList()
        }

        return try {
            codec.decode(content, PersistedStateEventEnvelope::class.java)?.events.orEmpty()
        } catch (_: JsonSyntaxException) {
            emptyList()
        }
    }
}

private data class PersistedStateEventEnvelope(
    val schemaVersion: Int = 1,
    val events: List<PersistedStateEvent> = emptyList(),
)

private data class PersistedStateEvent(
    val eventId: String,
    val scopeKey: String,
    val traceId: String? = null,
    val nodeId: String? = null,
    val eventType: StateEventType,
    val state: com.personalagent.bertbot.graph.model.BertBotState,
    val metadata: Map<String, String> = emptyMap(),
    val createdAtEpochMillis: Long,
) {
    fun toDomain(): StateEvent =
        StateEvent(
            eventId = eventId,
            scopeKey = scopeKey,
            traceId = traceId,
            nodeId = nodeId,
            eventType = eventType,
            state = state.copyForPersistence(),
            metadata = metadata.toMap(),
            createdAtEpochMillis = createdAtEpochMillis,
        )

    companion object {
        fun fromDomain(event: StateEvent): PersistedStateEvent =
            PersistedStateEvent(
                eventId = event.eventId,
                scopeKey = event.scopeKey,
                traceId = event.traceId,
                nodeId = event.nodeId,
                eventType = event.eventType,
                state = event.state.copyForPersistence(),
                metadata = event.metadata.toMap(),
                createdAtEpochMillis = event.createdAtEpochMillis,
            )
    }
}

private fun writeTextAtomically(
    target: File,
    content: String,
) {
    val parentDir = target.parentFile ?: File(".")
    parentDir.mkdirs()
    val tempPath = Files.createTempFile(parentDir.toPath(), "${target.nameWithoutExtension}-", ".tmp")
    val tempFile = tempPath.toFile()
    try {
        tempFile.writeText(content)
        try {
            Files.move(tempPath, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tempPath, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } catch (e: Exception) {
        runCatching { tempFile.delete() }
        throw e
    }
}
