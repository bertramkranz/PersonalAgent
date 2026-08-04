package com.personalagent.bertbot.app

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.personalagent.bertbot.graph.store.DriverManagerJdbcConnectionProvider
import com.personalagent.bertbot.memory.PersistenceScopeKey
import java.io.File
import java.sql.Connection
import java.time.Instant

internal enum class SessionHistoryRole {
    USER,
    ASSISTANT,
}

internal data class SessionHistoryEntry(
    val timestamp: String,
    val role: SessionHistoryRole,
    val text: String,
    val traceId: String? = null,
    val source: String = "chat",
)

internal interface SessionHistoryStore {
    fun append(entry: SessionHistoryEntry)

    fun list(limit: Int = 200): List<SessionHistoryEntry>

    fun search(
        query: String,
        limit: Int = 50,
    ): List<SessionHistoryEntry>

    fun clear()

    fun <T> withScope(
        scopeKey: String,
        action: () -> T,
    ): T = action()
}

internal class FileSessionHistoryStore(
    private val file: File,
    private val gson: Gson = Gson(),
    private val maxEntriesPerScope: Int,
) : SessionHistoryStore {
    private val lock = Any()
    private val currentScope = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }

    override fun append(entry: SessionHistoryEntry) {
        synchronized(lock) {
            val entries = loadEntriesForCurrentScope().toMutableList()
            entries.add(entry)
            val trimmed = trimToMax(entries)
            persistEntriesForCurrentScope(trimmed)
        }
    }

    override fun list(limit: Int): List<SessionHistoryEntry> {
        val bounded = limit.coerceIn(1, 10_000)
        synchronized(lock) {
            return loadEntriesForCurrentScope().takeLast(bounded)
        }
    }

    override fun search(
        query: String,
        limit: Int,
    ): List<SessionHistoryEntry> {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isBlank()) {
            return emptyList()
        }
        val bounded = limit.coerceIn(1, 10_000)
        synchronized(lock) {
            return loadEntriesForCurrentScope()
                .asReversed()
                .filter { it.text.lowercase().contains(normalizedQuery) }
                .take(bounded)
                .reversed()
        }
    }

    override fun clear() {
        synchronized(lock) {
            persistEntriesForCurrentScope(emptyList())
        }
    }

    override fun <T> withScope(
        scopeKey: String,
        action: () -> T,
    ): T {
        val previous = currentScope.get()
        currentScope.set(PersistenceScopeKey.normalizeForFile(scopeKey))
        return try {
            action()
        } finally {
            currentScope.set(previous)
        }
    }

    private fun loadEntriesForCurrentScope(): List<SessionHistoryEntry> {
        val scopedFile = scopedFile()
        if (!scopedFile.exists()) {
            return emptyList()
        }

        return scopedFile
            .readLines()
            .mapNotNull { line ->
                val raw = line.trim()
                if (raw.isBlank()) {
                    null
                } else {
                    runCatching { gson.fromJson(raw, SessionHistoryEntry::class.java) }
                        .getOrElse { error ->
                            if (error is JsonSyntaxException) {
                                null
                            } else {
                                throw error
                            }
                        }
                }
            }
    }

    private fun persistEntriesForCurrentScope(entries: List<SessionHistoryEntry>) {
        val scopedFile = scopedFile()
        scopedFile.parentFile?.mkdirs()
        val content = entries.joinToString(separator = "\n") { entry -> gson.toJson(entry) }
        scopedFile.writeText(content)
    }

    private fun scopedFile(): File {
        val scope = currentScope.get()
        if (scope == PersistenceScopeKey.defaultScopeKey()) {
            return file
        }

        val parent = file.parentFile ?: File(".")
        val stem = file.nameWithoutExtension
        val ext = file.extension.takeIf { it.isNotBlank() } ?: "jsonl"
        return File(parent, "$stem-$scope.$ext")
    }

    private fun trimToMax(entries: List<SessionHistoryEntry>): List<SessionHistoryEntry> {
        if (entries.size <= maxEntriesPerScope) {
            return entries
        }
        return entries.takeLast(maxEntriesPerScope)
    }
}

internal class JdbcSessionHistoryStore(
    jdbcUrl: String,
    username: String? = null,
    password: String? = null,
    private val tableName: String,
    private val gson: Gson = Gson(),
    private val maxEntriesPerScope: Int,
) : SessionHistoryStore {
    private val connectionProvider = DriverManagerJdbcConnectionProvider(jdbcUrl, username, password)
    private val lock = Any()
    private val currentScope = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }

    init {
        require(tableName.matches(TABLE_NAME_REGEX)) {
            "tableName must match ${TABLE_NAME_REGEX.pattern}"
        }
        initializeSchema()
    }

    override fun append(entry: SessionHistoryEntry) {
        synchronized(lock) {
            withConnection { connection ->
                connection.autoCommit = false
                try {
                    insertEntry(connection, currentScope.get(), entry)
                    trimExcessRows(connection, currentScope.get())
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

    override fun list(limit: Int): List<SessionHistoryEntry> {
        val bounded = limit.coerceIn(1, 10_000)
        synchronized(lock) {
            return withConnection { connection ->
                val sql =
                    """
                    SELECT payload
                    FROM $tableName
                    WHERE scope_key = ?
                    ORDER BY id DESC
                    LIMIT ?
                    """.trimIndent()
                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, currentScope.get())
                    statement.setInt(2, bounded)
                    statement.executeQuery().use { resultSet ->
                        val rows = mutableListOf<SessionHistoryEntry>()
                        while (resultSet.next()) {
                            val payload = resultSet.getString("payload")
                            runCatching {
                                gson.fromJson(payload, SessionHistoryEntry::class.java)
                            }.getOrNull()?.let { rows.add(it) }
                        }
                        rows.reversed()
                    }
                }
            }
        }
    }

    override fun search(
        query: String,
        limit: Int,
    ): List<SessionHistoryEntry> {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isBlank()) {
            return emptyList()
        }
        val bounded = limit.coerceIn(1, 10_000)
        synchronized(lock) {
            return withConnection { connection ->
                val sql =
                    """
                    SELECT payload
                    FROM $tableName
                    WHERE scope_key = ?
                      AND LOWER(payload) LIKE ?
                    ORDER BY id DESC
                    LIMIT ?
                    """.trimIndent()
                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, currentScope.get())
                    statement.setString(2, "%$normalizedQuery%")
                    statement.setInt(3, bounded)
                    statement.executeQuery().use { resultSet ->
                        val rows = mutableListOf<SessionHistoryEntry>()
                        while (resultSet.next()) {
                            val payload = resultSet.getString("payload")
                            runCatching {
                                gson.fromJson(payload, SessionHistoryEntry::class.java)
                            }.getOrNull()?.let { rows.add(it) }
                        }
                        rows.reversed()
                    }
                }
            }
        }
    }

    override fun clear() {
        synchronized(lock) {
            withConnection { connection ->
                connection.prepareStatement("DELETE FROM $tableName WHERE scope_key = ?").use { statement ->
                    statement.setString(1, currentScope.get())
                    statement.executeUpdate()
                }
            }
        }
    }

    override fun <T> withScope(
        scopeKey: String,
        action: () -> T,
    ): T {
        val previous = currentScope.get()
        currentScope.set(PersistenceScopeKey.normalizeForJdbc(scopeKey))
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
                        id BIGSERIAL PRIMARY KEY,
                        scope_key TEXT NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        payload TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE INDEX IF NOT EXISTS ${tableName}_scope_created_idx
                    ON $tableName (scope_key, id)
                    """.trimIndent(),
                )
            }
        }
    }

    private fun insertEntry(
        connection: Connection,
        scopeKey: String,
        entry: SessionHistoryEntry,
    ) {
        val sql = "INSERT INTO $tableName (scope_key, payload) VALUES (?, ?)"
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, scopeKey)
            statement.setString(2, gson.toJson(entry))
            statement.executeUpdate()
        }
    }

    private fun trimExcessRows(
        connection: Connection,
        scopeKey: String,
    ) {
        val sql =
            """
            DELETE FROM $tableName
            WHERE scope_key = ?
              AND id NOT IN (
                SELECT id
                FROM $tableName
                WHERE scope_key = ?
                ORDER BY id DESC
                LIMIT ?
              )
            """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, scopeKey)
            statement.setString(2, scopeKey)
            statement.setInt(3, maxEntriesPerScope)
            statement.executeUpdate()
        }
    }

    private fun <T> withConnection(action: (Connection) -> T): T {
        val connection = connectionProvider.open()
        connection.use { return action(it) }
    }

    private companion object {
        private val TABLE_NAME_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    }
}

internal object SessionHistoryStoreFactory {
    fun create(
        persistenceConfiguration: PersistenceRuntimeConfiguration,
        sessionHistoryConfiguration: SessionHistoryRuntimeConfiguration,
    ): SessionHistoryStore {
        val normalizedBackend = persistenceConfiguration.backend.lowercase()
        return when (normalizedBackend) {
            "jdbc", "postgres", "postgresql" -> {
                val jdbcUrl =
                    requireNotNull(persistenceConfiguration.jdbcUrl) {
                        "BERTBOT_STATE_JDBC_URL must be set when BERTBOT_STATE_STORE is '$normalizedBackend'."
                    }
                JdbcSessionHistoryStore(
                    jdbcUrl = jdbcUrl,
                    username = persistenceConfiguration.jdbcUser,
                    password = persistenceConfiguration.jdbcPassword,
                    tableName = persistenceConfiguration.sessionHistoryJdbcTable,
                    maxEntriesPerScope = sessionHistoryConfiguration.maxEntriesPerScope,
                )
            }
            else ->
                FileSessionHistoryStore(
                    file = File(persistenceConfiguration.sessionHistoryFilePath),
                    maxEntriesPerScope = sessionHistoryConfiguration.maxEntriesPerScope,
                )
        }
    }
}

internal fun buildSessionHistoryEntry(
    role: SessionHistoryRole,
    text: String,
    traceId: String?,
    source: String = "chat",
): SessionHistoryEntry =
    SessionHistoryEntry(
        timestamp = Instant.now().toString(),
        role = role,
        text = text,
        traceId = traceId,
        source = source,
    )
