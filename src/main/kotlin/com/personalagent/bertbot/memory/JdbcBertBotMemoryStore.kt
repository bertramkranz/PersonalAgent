package com.personalagent.bertbot.memory

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

@Suppress("TooManyFunctions")
class JdbcBertBotMemoryStore(
    private val jdbcUrl: String,
    private val username: String? = null,
    private val password: String? = null,
    private val tableName: String,
    private val gson: Gson = Gson(),
) : BertBotMemoryStore {
    private val lock = Any()
    private val entries = mutableListOf<MemoryEntry>()
    private val currentScope = ThreadLocal.withInitial { DEFAULT_SCOPE_KEY }
    private var loadedScopeKey: String? = null

    init {
        require(jdbcUrl.isNotBlank()) { "jdbcUrl must not be blank" }
        require(tableName.matches(TABLE_NAME_REGEX)) {
            "tableName must match ${TABLE_NAME_REGEX.pattern}"
        }
        initializeSchema()
        load()
    }

    override fun load(): List<MemoryEntry> {
        synchronized(lock) {
            forceReloadForCurrentScope()
            return entries.toList()
        }
    }

    override fun remember(text: String) {
        synchronized(lock) {
            ensureLoadedForCurrentScope()
            if (text.isBlank()) {
                return
            }
            entries.add(MemoryEntry(text = text.trim()))
            persist()
        }
    }

    override fun remember(entry: MemoryEntry) {
        synchronized(lock) {
            ensureLoadedForCurrentScope()
            if (entry.text.isBlank()) {
                return
            }
            entries.add(entry.normalized())
            persist()
        }
    }

    override fun entries(): List<MemoryEntry> =
        synchronized(lock) {
            ensureLoadedForCurrentScope()
            entries.toList()
        }

    override fun replaceAll(newEntries: List<MemoryEntry>) {
        synchronized(lock) {
            ensureLoadedForCurrentScope()
            entries.clear()
            entries.addAll(newEntries.filter { it.text.isNotBlank() }.map { it.normalized() })
            persist()
        }
    }

    override fun snapshot(): String {
        val current = entries()
        if (current.isEmpty()) {
            return "No remembered context yet."
        }

        return current.joinToString(separator = System.lineSeparator()) { "- ${it.text}" }
    }

    override fun count(): Int =
        synchronized(lock) {
            ensureLoadedForCurrentScope()
            entries.size
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

    private fun ensureLoadedForCurrentScope() {
        val scopeKey = currentScope.get()
        if (loadedScopeKey == scopeKey) {
            return
        }
        forceReloadForCurrentScope()
    }

    private fun forceReloadForCurrentScope() {
        entries.clear()
        val scopeKey = currentScope.get()
        val payload =
            try {
                readScopedPayload(scopeKey)
            } catch (_: SQLException) {
                readLegacyPayload()
            }
        if (!payload.isNullOrBlank()) {
            entries.addAll(parsePayloadEntries(payload))
        }
        loadedScopeKey = scopeKey
    }

    private fun persist() {
        val scopeKey = currentScope.get()
        withConnection { connection ->
            connection.autoCommit = false
            try {
                val payload = gson.toJson(entries)
                try {
                    val updated = updateScopedPayload(connection, scopeKey, payload)
                    if (updated == 0) {
                        try {
                            insertScopedPayload(connection, scopeKey, payload)
                        } catch (_: SQLException) {
                            // Concurrent writer inserted the row first; retry as an update.
                            updateScopedPayload(connection, scopeKey, payload)
                        }
                    }
                } catch (_: SQLException) {
                    val updated = updateLegacyPayload(connection, payload)
                    if (updated == 0) {
                        insertLegacyPayload(connection, payload)
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

    private fun readScopedPayload(scopeKey: String): String? =
        withConnection { connection ->
            val sql = "SELECT payload FROM $tableName WHERE scope_key = ?"
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, scopeKey)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        return@withConnection null
                    }
                    return@withConnection resultSet.getString("payload")
                }
            }
        }

    private fun readLegacyPayload(): String? =
        withConnection { connection ->
            val sql = "SELECT payload FROM $tableName WHERE snapshot_id = ?"
            connection.prepareStatement(sql).use { statement ->
                statement.setInt(1, LEGACY_SNAPSHOT_ID)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        return@withConnection null
                    }
                    return@withConnection resultSet.getString("payload")
                }
            }
        }

    private fun parsePayloadEntries(payload: String): List<MemoryEntry> {
        if (payload.isBlank()) {
            return emptyList()
        }

        return try {
            gson.fromJson(payload, Array<MemoryEntry>::class.java)?.map { it.normalized() } ?: emptyList()
        } catch (_: JsonSyntaxException) {
            println("Warning: failed to parse persisted memory row from table '$tableName'.")
            emptyList()
        }
    }

    private fun updateScopedPayload(
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

    private fun insertScopedPayload(
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

    private fun updateLegacyPayload(
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

    private fun insertLegacyPayload(
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

    private fun <T> withConnection(action: (Connection) -> T): T {
        val connection = if (username != null) DriverManager.getConnection(jdbcUrl, username, password) else DriverManager.getConnection(jdbcUrl)
        connection.use { return action(it) }
    }

    private companion object {
        private const val DEFAULT_SCOPE_KEY: String = "global"
        private const val LEGACY_SNAPSHOT_ID: Int = 1
        private val TABLE_NAME_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

        private fun normalizeScope(scopeKey: String): String =
            scopeKey.trim().ifBlank { DEFAULT_SCOPE_KEY }.take(255)
    }
}
