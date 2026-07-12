package com.personalagent.bertbot.graph.store

import com.personalagent.bertbot.graph.runtime.StateEvent
import com.personalagent.bertbot.graph.runtime.StateEventStore
import com.personalagent.bertbot.graph.runtime.StateEventType
import com.personalagent.bertbot.graph.runtime.copyForPersistence
import com.personalagent.bertbot.serialization.AgentJsonCodec
import com.personalagent.bertbot.serialization.GsonAgentJsonCodec
import java.sql.Connection
import java.sql.DriverManager

internal class JdbcStateEventStore(
    private val jdbcUrl: String,
    private val username: String? = null,
    private val password: String? = null,
    private val tableName: String = DEFAULT_TABLE_NAME,
    private val codec: AgentJsonCodec = GsonAgentJsonCodec(),
) : StateEventStore {
    init {
        require(jdbcUrl.isNotBlank()) { "jdbcUrl must not be blank" }
        require(tableName.matches(TABLE_NAME_REGEX)) {
            "tableName must match ${TABLE_NAME_REGEX.pattern}"
        }
        initializeSchema()
    }

    override fun append(event: StateEvent) {
        withConnection { connection ->
            connection.prepareStatement(
                "INSERT INTO $tableName (event_id, scope_key, created_at_epoch_millis, payload) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, event.eventId)
                statement.setString(2, event.scopeKey)
                statement.setLong(3, event.createdAtEpochMillis)
                statement.setString(4, codec.encode(JdbcPersistedStateEvent.fromDomain(event)))
                statement.executeUpdate()
            }
        }
    }

    override fun list(scopeKey: String): List<StateEvent> =
        withConnection { connection ->
            connection.prepareStatement(
                "SELECT payload FROM $tableName WHERE scope_key = ? ORDER BY created_at_epoch_millis ASC, event_id ASC",
            ).use { statement ->
                statement.setString(1, scopeKey)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            val payload = resultSet.getString("payload") ?: continue
                            val persisted = codec.decode(payload, JdbcPersistedStateEvent::class.java) ?: continue
                            add(persisted.toDomain())
                        }
                    }
                }
            }
        }

    private fun initializeSchema() {
        withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS $tableName (
                        event_id TEXT NOT NULL,
                        scope_key TEXT NOT NULL,
                        created_at_epoch_millis BIGINT NOT NULL,
                        payload TEXT NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (event_id)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_${tableName}_scope_created_at
                    ON $tableName (scope_key, created_at_epoch_millis)
                    """.trimIndent(),
                )
            }
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
        private const val DEFAULT_TABLE_NAME = "bertbot_state_event"
        private val TABLE_NAME_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    }
}

private data class JdbcPersistedStateEvent(
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
        fun fromDomain(event: StateEvent): JdbcPersistedStateEvent =
            JdbcPersistedStateEvent(
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
