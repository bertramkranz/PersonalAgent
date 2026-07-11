package com.personalagent.bertbot.ingestion

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

class JdbcConsentStore(
    private val jdbcUrl: String,
    private val username: String? = null,
    private val password: String? = null,
    private val tableName: String,
    private val gson: Gson = Gson(),
) : ConsentStore {
    private val lock = Any()

    init {
        require(jdbcUrl.isNotBlank()) { "jdbcUrl must not be blank" }
        require(tableName.matches(TABLE_NAME_REGEX)) {
            "tableName must match ${TABLE_NAME_REGEX.pattern}"
        }
        initializeSchema()
    }

    override fun load(): List<ApprovalRecord> {
        synchronized(lock) {
            val payload = readPayload().orEmpty()
            if (payload.isBlank()) {
                return emptyList()
            }

            return try {
                val snapshot = gson.fromJson(payload, JdbcPersistedConsentState::class.java)
                snapshot.toRecords()
            } catch (_: JsonSyntaxException) {
                println("Warning: failed to parse persisted ingestion consent row from table '$tableName'.")
                emptyList()
            }
        }
    }

    override fun upsert(record: ApprovalRecord) {
        synchronized(lock) {
            withConnection { connection ->
                connection.autoCommit = false
                try {
                    val current = readPayload(connection, forUpdate = true).parseConsentRecords(gson).toMutableList()
                    val existingIndex = current.indexOfFirst { it.source.key() == record.source.key() }
                    if (existingIndex >= 0) {
                        current[existingIndex] = record
                    } else {
                        current.add(record)
                    }

                    val payload = gson.toJson(JdbcPersistedConsentState.fromRecords(current))
                    writePayload(connection, payload)
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

    override fun isApproved(source: IngestionSource): Boolean {
        val records = load()
        val match = records.lastOrNull { it.source.key() == source.key() } ?: return false
        return match.approved
    }

    override fun listApproved(): List<ApprovalRecord> =
        load().filter { it.approved }.sortedBy { it.source.key() }

    private fun initializeSchema() {
        withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS $tableName (
                        snapshot_id INTEGER PRIMARY KEY,
                        payload TEXT NOT NULL,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """.trimIndent(),
                )
            }
            ensureSnapshotRow(connection)
        }
    }

    private fun ensureSnapshotRow(connection: Connection) {
        val sql = "INSERT INTO $tableName (snapshot_id, payload) VALUES (?, ?)"
        try {
            connection.prepareStatement(sql).use { statement ->
                statement.setInt(1, SNAPSHOT_ID)
                statement.setString(2, "")
                statement.executeUpdate()
            }
        } catch (_: SQLException) {
            // Snapshot row already exists.
        }
    }

    private fun readPayload(): String? =
        withConnection { connection ->
            readPayload(connection)
        }

    private fun readPayload(
        connection: Connection,
        forUpdate: Boolean = false,
    ): String? {
        val baseSql = "SELECT payload FROM $tableName WHERE snapshot_id = ?"
        val sql = if (forUpdate) "$baseSql FOR UPDATE" else baseSql
        return try {
            queryPayload(connection, sql)
        } catch (e: SQLException) {
            if (!forUpdate) {
                throw e
            }
            queryPayload(connection, baseSql)
        }
    }

    private fun queryPayload(
        connection: Connection,
        sql: String,
    ): String? =
        connection.prepareStatement(sql).use { statement ->
            statement.setInt(1, SNAPSHOT_ID)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return@use null
                }
                resultSet.getString("payload")
            }
        }

    private fun writePayload(
        connection: Connection,
        payload: String,
    ) {
        val updated = updatePayload(connection, payload)
        if (updated == 0) {
            insertPayload(connection, payload)
        }
    }

    private fun updatePayload(
        connection: Connection,
        payload: String,
    ): Int {
        val sql = "UPDATE $tableName SET payload = ?, updated_at = CURRENT_TIMESTAMP WHERE snapshot_id = ?"
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, payload)
            statement.setInt(2, SNAPSHOT_ID)
            return statement.executeUpdate()
        }
    }

    private fun insertPayload(
        connection: Connection,
        payload: String,
    ) {
        val sql = "INSERT INTO $tableName (snapshot_id, payload) VALUES (?, ?)"
        connection.prepareStatement(sql).use { statement ->
            statement.setInt(1, SNAPSHOT_ID)
            statement.setString(2, payload)
            statement.executeUpdate()
        }
    }

    private fun <T> withConnection(action: (Connection) -> T): T {
        val connection = if (username != null) DriverManager.getConnection(jdbcUrl, username, password) else DriverManager.getConnection(jdbcUrl)
        connection.use { return action(it) }
    }

    private companion object {
        private const val SNAPSHOT_ID: Int = 1
        private val TABLE_NAME_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    }
}

class JdbcSourceStateStore(
    private val jdbcUrl: String,
    private val username: String? = null,
    private val password: String? = null,
    private val tableName: String,
    private val gson: Gson = Gson(),
) : SourceStateStore {
    private val lock = Any()

    init {
        require(jdbcUrl.isNotBlank()) { "jdbcUrl must not be blank" }
        require(tableName.matches(TABLE_NAME_REGEX)) {
            "tableName must match ${TABLE_NAME_REGEX.pattern}"
        }
        initializeSchema()
    }

    override fun load(): List<SyncCursor> {
        synchronized(lock) {
            val payload = readPayload().orEmpty()
            if (payload.isBlank()) {
                return emptyList()
            }

            return try {
                val snapshot = gson.fromJson(payload, JdbcPersistedSourceState::class.java)
                snapshot.toCursors()
            } catch (_: JsonSyntaxException) {
                println("Warning: failed to parse persisted ingestion source-state row from table '$tableName'.")
                emptyList()
            }
        }
    }

    override fun upsert(cursor: SyncCursor) {
        synchronized(lock) {
            withConnection { connection ->
                connection.autoCommit = false
                try {
                    val current = readPayload(connection, forUpdate = true).parseSourceCursors(gson).toMutableList()
                    val existingIndex = current.indexOfFirst { it.source.key() == cursor.source.key() }
                    if (existingIndex >= 0) {
                        current[existingIndex] = cursor
                    } else {
                        current.add(cursor)
                    }

                    val payload = gson.toJson(JdbcPersistedSourceState.fromCursors(current))
                    writePayload(connection, payload)
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

    override fun find(source: IngestionSource): SyncCursor? =
        load().lastOrNull { it.source.key() == source.key() }

    private fun initializeSchema() {
        withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS $tableName (
                        snapshot_id INTEGER PRIMARY KEY,
                        payload TEXT NOT NULL,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """.trimIndent(),
                )
            }
            ensureSnapshotRow(connection)
        }
    }

    private fun ensureSnapshotRow(connection: Connection) {
        val sql = "INSERT INTO $tableName (snapshot_id, payload) VALUES (?, ?)"
        try {
            connection.prepareStatement(sql).use { statement ->
                statement.setInt(1, SNAPSHOT_ID)
                statement.setString(2, "")
                statement.executeUpdate()
            }
        } catch (_: SQLException) {
            // Snapshot row already exists.
        }
    }

    private fun readPayload(): String? =
        withConnection { connection ->
            readPayload(connection)
        }

    private fun readPayload(
        connection: Connection,
        forUpdate: Boolean = false,
    ): String? {
        val baseSql = "SELECT payload FROM $tableName WHERE snapshot_id = ?"
        val sql = if (forUpdate) "$baseSql FOR UPDATE" else baseSql
        return try {
            queryPayload(connection, sql)
        } catch (e: SQLException) {
            if (!forUpdate) {
                throw e
            }
            queryPayload(connection, baseSql)
        }
    }

    private fun queryPayload(
        connection: Connection,
        sql: String,
    ): String? =
        connection.prepareStatement(sql).use { statement ->
            statement.setInt(1, SNAPSHOT_ID)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return@use null
                }
                resultSet.getString("payload")
            }
        }

    private fun writePayload(
        connection: Connection,
        payload: String,
    ) {
        val updated = updatePayload(connection, payload)
        if (updated == 0) {
            insertPayload(connection, payload)
        }
    }

    private fun updatePayload(
        connection: Connection,
        payload: String,
    ): Int {
        val sql = "UPDATE $tableName SET payload = ?, updated_at = CURRENT_TIMESTAMP WHERE snapshot_id = ?"
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, payload)
            statement.setInt(2, SNAPSHOT_ID)
            return statement.executeUpdate()
        }
    }

    private fun insertPayload(
        connection: Connection,
        payload: String,
    ) {
        val sql = "INSERT INTO $tableName (snapshot_id, payload) VALUES (?, ?)"
        connection.prepareStatement(sql).use { statement ->
            statement.setInt(1, SNAPSHOT_ID)
            statement.setString(2, payload)
            statement.executeUpdate()
        }
    }

    private fun <T> withConnection(action: (Connection) -> T): T {
        val connection = if (username != null) DriverManager.getConnection(jdbcUrl, username, password) else DriverManager.getConnection(jdbcUrl)
        connection.use { return action(it) }
    }

    private companion object {
        private const val SNAPSHOT_ID: Int = 1
        private val TABLE_NAME_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    }
}

private fun String?.parseConsentRecords(gson: Gson): List<ApprovalRecord> {
    if (this.isNullOrBlank()) {
        return emptyList()
    }

    return try {
        gson.fromJson(this, JdbcPersistedConsentState::class.java)?.toRecords() ?: emptyList()
    } catch (_: JsonSyntaxException) {
        emptyList()
    }
}

private fun String?.parseSourceCursors(gson: Gson): List<SyncCursor> {
    if (this.isNullOrBlank()) {
        return emptyList()
    }

    return try {
        gson.fromJson(this, JdbcPersistedSourceState::class.java)?.toCursors() ?: emptyList()
    } catch (_: JsonSyntaxException) {
        emptyList()
    }
}

private data class JdbcPersistedConsentState(
    val schemaVersion: Int = 1,
    val approvals: List<ApprovalRecord> = emptyList(),
) {
    fun toRecords(): List<ApprovalRecord> {
        if (schemaVersion != 1) {
            return emptyList()
        }
        return approvals
    }

    companion object {
        fun fromRecords(records: List<ApprovalRecord>): JdbcPersistedConsentState =
            JdbcPersistedConsentState(
                schemaVersion = 1,
                approvals = records,
            )
    }
}

private data class JdbcPersistedSourceState(
    val schemaVersion: Int = 1,
    val cursors: List<SyncCursor> = emptyList(),
) {
    fun toCursors(): List<SyncCursor> {
        if (schemaVersion != 1) {
            return emptyList()
        }
        return cursors
    }

    companion object {
        fun fromCursors(cursors: List<SyncCursor>): JdbcPersistedSourceState =
            JdbcPersistedSourceState(
                schemaVersion = 1,
                cursors = cursors,
            )
    }
}

private fun IngestionSource.key(): String {
    val workspace = workspaceId ?: ""
    return "${platform.name}|${sourceKind.name}|$workspace|$sourceId"
}
