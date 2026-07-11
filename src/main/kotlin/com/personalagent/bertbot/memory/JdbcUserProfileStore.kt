package com.personalagent.bertbot.memory

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

internal interface UserProfilePersistence {
    fun load(): UserProfile

    fun current(): UserProfile

    fun updateDisplayName(displayName: String)

    fun addRecurringPreference(preference: String)

    fun addCommunicationStyleHint(hint: String)

    fun addStableInterest(interest: String)

    fun <T> withScope(
        scopeKey: String,
        action: () -> T,
    ): T = action()
}

internal class JdbcUserProfileStore(
    private val jdbcUrl: String,
    private val username: String? = null,
    private val password: String? = null,
    private val tableName: String,
    private val gson: Gson = Gson(),
) : UserProfilePersistence {
    private val lock = Any()
    private var cached = UserProfile()
    private val currentScope = ThreadLocal.withInitial { DEFAULT_SCOPE_KEY }
    private var loadedScopeKey: String? = null

    init {
        require(jdbcUrl.isNotBlank()) { "jdbcUrl must not be blank" }
        require(tableName.matches(TABLE_NAME_REGEX)) {
            "tableName must match ${TABLE_NAME_REGEX.pattern}"
        }
        initializeSchema()
        cached = load()
    }

    override fun load(): UserProfile {
        synchronized(lock) {
            val scopeKey = currentScope.get()
            if (loadedScopeKey != scopeKey) {
                cached = loadScope(scopeKey)
                loadedScopeKey = scopeKey
            }
            return cached
        }
    }

    private fun loadScope(scopeKey: String): UserProfile {
        val payload =
            try {
                readScopedPayload(scopeKey)
            } catch (_: SQLException) {
                readLegacyPayload()
            }
        if (payload.isNullOrBlank()) {
            return UserProfile()
        }

        return try {
            gson.fromJson(payload, UserProfile::class.java) ?: UserProfile()
        } catch (_: JsonSyntaxException) {
            println("Warning: failed to parse persisted user profile row from table '$tableName'.")
            UserProfile()
        }
    }

    override fun current(): UserProfile =
        synchronized(lock) {
            ensureLoadedForCurrentScope()
            cached
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

    override fun updateDisplayName(displayName: String) {
        synchronized(lock) {
            ensureLoadedForCurrentScope()
            val normalized = normalizeDisplayName(displayName)
            if (normalized.isBlank() || cached.displayName == normalized) {
                return
            }

            cached = cached.copy(displayName = normalized)
            persist()
        }
    }

    override fun addRecurringPreference(preference: String) {
        synchronized(lock) {
            ensureLoadedForCurrentScope()
            val normalized = normalizeLabel(preference)
            if (normalized.isBlank() || cached.recurringPreferences.contains(normalized)) {
                return
            }

            cached = cached.copy(recurringPreferences = normalizeSet(cached.recurringPreferences + normalized))
            persist()
        }
    }

    override fun addCommunicationStyleHint(hint: String) {
        synchronized(lock) {
            ensureLoadedForCurrentScope()
            val normalized = normalizeLabel(hint)
            if (normalized.isBlank() || cached.communicationStyleHints.contains(normalized)) {
                return
            }

            cached = cached.copy(communicationStyleHints = normalizeSet(cached.communicationStyleHints + normalized))
            persist()
        }
    }

    override fun addStableInterest(interest: String) {
        synchronized(lock) {
            ensureLoadedForCurrentScope()
            val normalized = normalizeLabel(interest)
            if (normalized.isBlank() || cached.stableInterests.contains(normalized)) {
                return
            }

            cached = cached.copy(stableInterests = normalizeSet(cached.stableInterests + normalized))
            persist()
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
        cached = loadScope(scopeKey)
        loadedScopeKey = scopeKey
    }

    private fun persist() {
        val scopeKey = currentScope.get()
        withConnection { connection ->
            connection.autoCommit = false
            try {
                val payload = gson.toJson(cached)
                try {
                    val updated = updateScopedPayload(connection, scopeKey, payload)
                    if (updated == 0) {
                        insertScopedPayload(connection, scopeKey, payload)
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
        loadedScopeKey = scopeKey
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
