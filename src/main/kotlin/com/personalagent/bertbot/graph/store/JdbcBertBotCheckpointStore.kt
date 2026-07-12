package com.personalagent.bertbot.graph.store

import com.personalagent.bertbot.graph.runtime.BertBotCheckpoint
import com.personalagent.bertbot.graph.runtime.BertBotCheckpointStore
import com.personalagent.bertbot.graph.runtime.copyForPersistence
import com.personalagent.bertbot.serialization.AgentJsonCodec
import com.personalagent.bertbot.serialization.GsonAgentJsonCodec
import java.sql.Connection
import java.sql.DriverManager

internal class JdbcBertBotCheckpointStore(
    private val jdbcUrl: String,
    private val username: String? = null,
    private val password: String? = null,
    private val tableName: String = DEFAULT_TABLE_NAME,
    private val codec: AgentJsonCodec = GsonAgentJsonCodec(),
) : BertBotCheckpointStore {
    init {
        require(jdbcUrl.isNotBlank()) { "jdbcUrl must not be blank" }
        require(tableName.matches(TABLE_NAME_REGEX)) {
            "tableName must match ${TABLE_NAME_REGEX.pattern}"
        }
        initializeSchema()
    }

    override fun save(checkpoint: BertBotCheckpoint) {
        withConnection { connection ->
            connection.autoCommit = false
            try {
                val payload = codec.encode(JdbcPersistedCheckpoint.fromDomain(checkpoint))
                val updated = updateCheckpoint(connection, checkpoint.scopeKey, checkpoint.checkpointId, checkpoint.createdAtEpochMillis, payload)
                if (updated == 0) {
                    insertCheckpoint(connection, checkpoint.scopeKey, checkpoint.checkpointId, checkpoint.createdAtEpochMillis, payload)
                }
                connection.commit()
            } catch (e: Exception) {
                runCatching { connection.rollback() }
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override fun loadLatest(scopeKey: String): BertBotCheckpoint? =
        withConnection { connection ->
            connection.prepareStatement(
                "SELECT payload FROM $tableName WHERE scope_key = ? ORDER BY created_at_epoch_millis DESC LIMIT 1",
            ).use { statement ->
                statement.setString(1, scopeKey)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        return@withConnection null
                    }
                    decodeCheckpoint(resultSet.getString("payload"))
                }
            }
        }

    override fun loadById(
        scopeKey: String,
        checkpointId: String,
    ): BertBotCheckpoint? =
        withConnection { connection ->
            connection.prepareStatement(
                "SELECT payload FROM $tableName WHERE scope_key = ? AND checkpoint_id = ?",
            ).use { statement ->
                statement.setString(1, scopeKey)
                statement.setString(2, checkpointId)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        return@withConnection null
                    }
                    decodeCheckpoint(resultSet.getString("payload"))
                }
            }
        }

    override fun list(scopeKey: String): List<BertBotCheckpoint> =
        withConnection { connection ->
            connection.prepareStatement(
                "SELECT payload FROM $tableName WHERE scope_key = ? ORDER BY created_at_epoch_millis ASC",
            ).use { statement ->
                statement.setString(1, scopeKey)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            decodeCheckpoint(resultSet.getString("payload"))?.let { add(it) }
                        }
                    }
                }
            }
        }

    private fun decodeCheckpoint(payload: String?): BertBotCheckpoint? {
        if (payload.isNullOrBlank()) return null
        val persisted = codec.decode(payload, JdbcPersistedCheckpoint::class.java) ?: return null
        return persisted.toDomain()
    }

    private fun initializeSchema() {
        withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS $tableName (
                        scope_key TEXT NOT NULL,
                        checkpoint_id TEXT NOT NULL,
                        created_at_epoch_millis BIGINT NOT NULL,
                        payload TEXT NOT NULL,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (scope_key, checkpoint_id)
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    private fun updateCheckpoint(
        connection: Connection,
        scopeKey: String,
        checkpointId: String,
        createdAtEpochMillis: Long,
        payload: String,
    ): Int {
        connection.prepareStatement(
            "UPDATE $tableName SET created_at_epoch_millis = ?, payload = ?, updated_at = CURRENT_TIMESTAMP WHERE scope_key = ? AND checkpoint_id = ?",
        ).use { statement ->
            statement.setLong(1, createdAtEpochMillis)
            statement.setString(2, payload)
            statement.setString(3, scopeKey)
            statement.setString(4, checkpointId)
            return statement.executeUpdate()
        }
    }

    private fun insertCheckpoint(
        connection: Connection,
        scopeKey: String,
        checkpointId: String,
        createdAtEpochMillis: Long,
        payload: String,
    ) {
        connection.prepareStatement(
            "INSERT INTO $tableName (scope_key, checkpoint_id, created_at_epoch_millis, payload) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, scopeKey)
            statement.setString(2, checkpointId)
            statement.setLong(3, createdAtEpochMillis)
            statement.setString(4, payload)
            statement.executeUpdate()
        }
    }

    private fun <T> withConnection(action: (Connection) -> T): T {
        val connection =
            if (username != null) {
                DriverManager.getConnection(jdbcUrl, username, password)
            } else {
                DriverManager.getConnection(jdbcUrl)
            }
        connection.use { return action(it) }
    }

    private companion object {
        private const val DEFAULT_TABLE_NAME = "bertbot_checkpoint_snapshot"
        private val TABLE_NAME_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    }
}

private data class JdbcPersistedCheckpoint(
    val checkpointId: String,
    val scopeKey: String,
    val traceId: String? = null,
    val nodeId: String? = null,
    val state: com.personalagent.bertbot.graph.model.BertBotState,
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
        fun fromDomain(checkpoint: BertBotCheckpoint): JdbcPersistedCheckpoint =
            JdbcPersistedCheckpoint(
                checkpointId = checkpoint.checkpointId,
                scopeKey = checkpoint.scopeKey,
                traceId = checkpoint.traceId,
                nodeId = checkpoint.nodeId,
                state = checkpoint.state.copyForPersistence(),
                createdAtEpochMillis = checkpoint.createdAtEpochMillis,
            )
    }
}
