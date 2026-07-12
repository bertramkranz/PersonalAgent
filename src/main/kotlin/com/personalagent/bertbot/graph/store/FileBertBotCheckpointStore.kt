package com.personalagent.bertbot.graph.store

import com.google.gson.JsonSyntaxException
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.BertBotCheckpoint
import com.personalagent.bertbot.graph.runtime.BertBotCheckpointStore
import com.personalagent.bertbot.graph.runtime.copyForPersistence
import com.personalagent.bertbot.serialization.AgentJsonCodec
import com.personalagent.bertbot.serialization.GsonAgentJsonCodec
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class FileBertBotCheckpointStore(
    private val file: File,
    private val codec: AgentJsonCodec = GsonAgentJsonCodec(),
) : BertBotCheckpointStore {
    private val lock = Any()

    override fun save(checkpoint: BertBotCheckpoint) {
        synchronized(lock) {
            val payload = loadPayloadInternal().toMutableList()
            payload.removeIf { it.scopeKey == checkpoint.scopeKey && it.checkpointId == checkpoint.checkpointId }
            payload.add(PersistedCheckpoint.fromDomain(checkpoint))
            val persisted = PersistedCheckpointEnvelope(checkpoints = payload)
            file.parentFile?.mkdirs()
            writeTextAtomically(file, codec.encode(persisted))
        }
    }

    override fun loadLatest(scopeKey: String): BertBotCheckpoint? {
        synchronized(lock) {
            return loadPayloadInternal()
                .asSequence()
                .filter { it.scopeKey == scopeKey }
                .maxByOrNull { it.createdAtEpochMillis }
                ?.toDomain()
        }
    }

    override fun loadById(
        scopeKey: String,
        checkpointId: String,
    ): BertBotCheckpoint? {
        synchronized(lock) {
            return loadPayloadInternal()
                .firstOrNull { it.scopeKey == scopeKey && it.checkpointId == checkpointId }
                ?.toDomain()
        }
    }

    override fun list(scopeKey: String): List<BertBotCheckpoint> {
        synchronized(lock) {
            return loadPayloadInternal()
                .asSequence()
                .filter { it.scopeKey == scopeKey }
                .sortedBy { it.createdAtEpochMillis }
                .map { it.toDomain() }
                .toList()
        }
    }

    private fun loadPayloadInternal(): List<PersistedCheckpoint> {
        if (!file.exists()) {
            return emptyList()
        }

        val content = file.readText()
        if (content.isBlank()) {
            return emptyList()
        }

        return try {
            codec.decode(content, PersistedCheckpointEnvelope::class.java)?.checkpoints.orEmpty()
        } catch (_: JsonSyntaxException) {
            emptyList()
        }
    }
}

private data class PersistedCheckpointEnvelope(
    val schemaVersion: Int = 1,
    val checkpoints: List<PersistedCheckpoint> = emptyList(),
)

private data class PersistedCheckpoint(
    val checkpointId: String,
    val scopeKey: String,
    val traceId: String? = null,
    val nodeId: String? = null,
    val state: BertBotState,
    val createdAtEpochMillis: Long,
) {
    fun toDomain(): BertBotCheckpoint =
        BertBotCheckpoint(
            checkpointId = checkpointId,
            scopeKey = scopeKey,
            traceId = traceId,
            nodeId = nodeId,
            state = state.copyForPersistence(),
            createdAtEpochMillis = createdAtEpochMillis,
        )

    companion object {
        fun fromDomain(checkpoint: BertBotCheckpoint): PersistedCheckpoint =
            PersistedCheckpoint(
                checkpointId = checkpoint.checkpointId,
                scopeKey = checkpoint.scopeKey,
                traceId = checkpoint.traceId,
                nodeId = checkpoint.nodeId,
                state = checkpoint.state.copyForPersistence(),
                createdAtEpochMillis = checkpoint.createdAtEpochMillis,
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
