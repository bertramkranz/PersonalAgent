package com.personalagent.bertbot.graph.store

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.personalagent.bertbot.graph.model.BertBotState
import com.personalagent.bertbot.graph.runtime.BertBotStateStore
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

class JdbcBertBotStateStore(
    private val jdbcUrl: String,
    private val username: String? = null,
    private val password: String? = null,
    private val tableName: String = DEFAULT_TABLE_NAME,
    private val gson: Gson = Gson(),
) : BertBotStateStore {
    private val lock = Any()
    private val currentScope = ThreadLocal.withInitial { DEFAULT_SCOPE_KEY }

    init {
        require(jdbcUrl.isNotBlank()) { "jdbcUrl must not be blank" }
        require(tableName.matches(TABLE_NAME_REGEX)) {
            "tableName must match ${TABLE_NAME_REGEX.pattern}"
        }
        initializeSchema()
    }

    override fun load(): BertBotState =
        synchronized(lock) {
            val scopeKey = currentScope.get()
            withConnection { connection ->
                val payload =
                    try {
                        loadScopedPayload(connection, scopeKey)
                    } catch (_: SQLException) {
                        loadLegacyPayload(connection)
                    }

                if (payload.isNullOrBlank()) {
                    return@withConnection BertBotState()
                }

                return@withConnection parsePersistedState(payload)
            }
        }

    override fun save(state: BertBotState) {
        synchronized(lock) {
            val scopeKey = currentScope.get()
            withConnection { connection ->
                connection.autoCommit = false
                try {
                    val payload = gson.toJson(PersistedBertBotStateSnapshot.fromState(state))
                    try {
                        val updated = updateScopedSnapshot(connection, scopeKey, payload)
                        if (updated == 0) {
                            insertScopedSnapshot(connection, scopeKey, payload)
                        }
                    } catch (_: SQLException) {
                        val updated = updateLegacySnapshot(connection, payload)
                        if (updated == 0) {
                            insertLegacySnapshot(connection, payload)
                        }
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
    }

    override fun <T> withScope(
        scopeKey: String,
        action: () -> T,
    ): T {
        val previous = currentScope.get()
        currentScope.set(normalizeScope(scopeKey))
        return try {
            action()
        } finally {
            currentScope.set(previous)
        }
    }

    private fun initializeSchema() {
        withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS $tableName (
                        scope_key TEXT PRIMARY KEY,
                        payload TEXT NOT NULL,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    private fun loadScopedPayload(
        connection: Connection,
        scopeKey: String,
    ): String? {
        val sql = "SELECT payload FROM $tableName WHERE scope_key = ?"
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, scopeKey)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return null
                }
                return resultSet.getString("payload")
            }
        }
    }

    private fun loadLegacyPayload(connection: Connection): String? {
        val sql = "SELECT payload FROM $tableName WHERE snapshot_id = ?"
        connection.prepareStatement(sql).use { statement ->
            statement.setInt(1, LEGACY_SNAPSHOT_ID)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return null
                }
                return resultSet.getString("payload")
            }
        }
    }

    private fun updateScopedSnapshot(
        connection: Connection,
        scopeKey: String,
        payload: String,
    ): Int {
        val sql = "UPDATE $tableName SET payload = ?, updated_at = CURRENT_TIMESTAMP WHERE scope_key = ?"
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, payload)
            statement.setString(2, scopeKey)
            return statement.executeUpdate()
        }
    }

    private fun insertScopedSnapshot(
        connection: Connection,
        scopeKey: String,
        payload: String,
    ) {
        val sql = "INSERT INTO $tableName (scope_key, payload) VALUES (?, ?)"
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, scopeKey)
            statement.setString(2, payload)
            statement.executeUpdate()
        }
    }

    private fun updateLegacySnapshot(
        connection: Connection,
        payload: String,
    ): Int {
        val sql = "UPDATE $tableName SET payload = ?, updated_at = CURRENT_TIMESTAMP WHERE snapshot_id = ?"
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, payload)
            statement.setInt(2, LEGACY_SNAPSHOT_ID)
            return statement.executeUpdate()
        }
    }

    private fun insertLegacySnapshot(
        connection: Connection,
        payload: String,
    ) {
        val sql = "INSERT INTO $tableName (snapshot_id, payload) VALUES (?, ?)"
        connection.prepareStatement(sql).use { statement ->
            statement.setInt(1, LEGACY_SNAPSHOT_ID)
            statement.setString(2, payload)
            statement.executeUpdate()
        }
    }

    private fun parsePersistedState(payload: String): BertBotState {
        return try {
            val snapshot = gson.fromJson(payload, PersistedBertBotStateSnapshot::class.java)
            if (snapshot?.schemaVersion == CURRENT_SCHEMA_VERSION) {
                return snapshot.toState()
            }

            val legacySnapshot = gson.fromJson(payload, LegacyPersistedBertBotStateSnapshot::class.java)
            if (legacySnapshot?.schemaVersion == LEGACY_SCHEMA_VERSION && legacySnapshot.state != null) {
                return legacySnapshot.state
            }

            gson.fromJson(payload, BertBotState::class.java) ?: BertBotState()
        } catch (_: JsonSyntaxException) {
            println("Warning: failed to parse persisted state row from table '$tableName'.")
            BertBotState()
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
        private const val DEFAULT_SCOPE_KEY = "global"
        private const val LEGACY_SNAPSHOT_ID: Int = 1
        private const val DEFAULT_TABLE_NAME: String = "bertbot_state_snapshot"
        private val TABLE_NAME_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
        private const val CURRENT_SCHEMA_VERSION: Int = 2
        private const val LEGACY_SCHEMA_VERSION: Int = 1

        private fun normalizeScope(scopeKey: String): String =
            scopeKey.trim().ifBlank { DEFAULT_SCOPE_KEY }.take(255)
    }
}
