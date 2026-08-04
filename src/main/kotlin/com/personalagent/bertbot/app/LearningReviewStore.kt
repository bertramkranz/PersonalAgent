package com.personalagent.bertbot.app

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.personalagent.bertbot.graph.store.DriverManagerJdbcConnectionProvider
import com.personalagent.bertbot.memory.PersistenceScopeKey
import java.io.File
import java.sql.Connection
import java.time.Instant
import java.util.UUID

internal enum class LearningReviewWriteType {
    MEMORY,
    SKILL,
}

internal enum class LearningReviewStatus {
    PENDING,
    APPROVED,
    REJECTED,
}

internal data class LearningReviewRequest(
    val requestId: String,
    val createdAt: String,
    val scopeKey: String,
    val writeType: LearningReviewWriteType,
    val payload: String,
    val traceId: String? = null,
    val status: LearningReviewStatus = LearningReviewStatus.PENDING,
    val decidedAt: String? = null,
    val decisionNote: String? = null,
    val lastApplyFailedAt: String? = null,
    val lastApplyFailureReason: String? = null,
)

internal interface LearningReviewStore {
    fun enqueue(request: LearningReviewRequest)

    fun list(
        status: LearningReviewStatus? = null,
        limit: Int = 200,
    ): List<LearningReviewRequest>

    fun decide(
        requestId: String,
        status: LearningReviewStatus,
        note: String? = null,
    ): LearningReviewRequest?

    fun recordApplyFailure(
        requestId: String,
        reason: String,
    ): LearningReviewRequest?

    fun <T> withScope(
        scopeKey: String,
        action: () -> T,
    ): T = action()
}

internal class FileLearningReviewStore(
    private val file: File,
    private val gson: Gson = Gson(),
) : LearningReviewStore {
    private val lock = Any()
    private val currentScope = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }

    override fun enqueue(request: LearningReviewRequest) {
        synchronized(lock) {
            val all = loadAllForCurrentScope().toMutableList()
            all += request
            persistForCurrentScope(all)
        }
    }

    override fun list(
        status: LearningReviewStatus?,
        limit: Int,
    ): List<LearningReviewRequest> {
        val bounded = limit.coerceIn(1, 10_000)
        synchronized(lock) {
            val all = loadAllForCurrentScope()
            val filtered = if (status == null) all else all.filter { it.status == status }
            return filtered.takeLast(bounded)
        }
    }

    override fun decide(
        requestId: String,
        status: LearningReviewStatus,
        note: String?,
    ): LearningReviewRequest? {
        synchronized(lock) {
            val all = loadAllForCurrentScope().toMutableList()
            val index = all.indexOfFirst { it.requestId == requestId }
            if (index < 0) {
                return null
            }
            val existing = all[index]
            val decided =
                existing.copy(
                    status = status,
                    decidedAt = Instant.now().toString(),
                    decisionNote = note?.trim()?.ifBlank { null },
                )
            all[index] = decided
            persistForCurrentScope(all)
            return decided
        }
    }

    override fun recordApplyFailure(
        requestId: String,
        reason: String,
    ): LearningReviewRequest? {
        synchronized(lock) {
            val all = loadAllForCurrentScope().toMutableList()
            val index = all.indexOfFirst { it.requestId == requestId }
            if (index < 0) {
                return null
            }
            val existing = all[index]
            val updated =
                existing.copy(
                    lastApplyFailedAt = Instant.now().toString(),
                    lastApplyFailureReason = reason.trim().ifBlank { "apply failed" },
                )
            all[index] = updated
            persistForCurrentScope(all)
            return updated
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

    private fun loadAllForCurrentScope(): List<LearningReviewRequest> {
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
                    runCatching { gson.fromJson(raw, LearningReviewRequest::class.java) }
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

    private fun persistForCurrentScope(entries: List<LearningReviewRequest>) {
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
}

internal class JdbcLearningReviewStore(
    jdbcUrl: String,
    username: String? = null,
    password: String? = null,
    private val queueTable: String,
    private val decisionTable: String,
    private val gson: Gson = Gson(),
) : LearningReviewStore {
    private val connectionProvider = DriverManagerJdbcConnectionProvider(jdbcUrl, username, password)
    private val lock = Any()
    private val currentScope = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }

    init {
        require(queueTable.matches(TABLE_NAME_REGEX)) { "queueTable must match ${TABLE_NAME_REGEX.pattern}" }
        require(decisionTable.matches(TABLE_NAME_REGEX)) { "decisionTable must match ${TABLE_NAME_REGEX.pattern}" }
        initializeSchema()
    }

    override fun enqueue(request: LearningReviewRequest) {
        synchronized(lock) {
            withConnection { connection ->
                connection.prepareStatement(
                    "INSERT INTO $queueTable (scope_key, request_id, payload) VALUES (?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, currentScope.get())
                    statement.setString(2, request.requestId)
                    statement.setString(3, gson.toJson(request))
                    statement.executeUpdate()
                }
            }
        }
    }

    override fun list(
        status: LearningReviewStatus?,
        limit: Int,
    ): List<LearningReviewRequest> {
        val bounded = limit.coerceIn(1, 10_000)
        synchronized(lock) {
            return withConnection { connection ->
                val baseSql =
                    if (status == null) {
                        "SELECT payload FROM $queueTable WHERE scope_key = ? ORDER BY id DESC LIMIT ?"
                    } else {
                        "SELECT payload FROM $queueTable WHERE scope_key = ? AND status = ? ORDER BY id DESC LIMIT ?"
                    }
                connection.prepareStatement(baseSql).use { statement ->
                    statement.setString(1, currentScope.get())
                    if (status == null) {
                        statement.setInt(2, bounded)
                    } else {
                        statement.setString(2, status.name)
                        statement.setInt(3, bounded)
                    }
                    statement.executeQuery().use { rs ->
                        val rows = mutableListOf<LearningReviewRequest>()
                        while (rs.next()) {
                            runCatching {
                                gson.fromJson(rs.getString("payload"), LearningReviewRequest::class.java)
                            }.getOrNull()?.let { rows += it }
                        }
                        rows.reversed()
                    }
                }
            }
        }
    }

    override fun decide(
        requestId: String,
        status: LearningReviewStatus,
        note: String?,
    ): LearningReviewRequest? {
        synchronized(lock) {
            return withConnection { connection ->
                connection.autoCommit = false
                try {
                    val existing =
                        findById(connection, requestId)
                            ?: run {
                                connection.rollback()
                                return@withConnection null
                            }
                    val decided =
                        existing.copy(
                            status = status,
                            decidedAt = Instant.now().toString(),
                            decisionNote = note?.trim()?.ifBlank { null },
                        )
                    connection.prepareStatement(
                        "UPDATE $queueTable SET status = ?, payload = ? WHERE scope_key = ? AND request_id = ?",
                    ).use { statement ->
                        statement.setString(1, decided.status.name)
                        statement.setString(2, gson.toJson(decided))
                        statement.setString(3, currentScope.get())
                        statement.setString(4, requestId)
                        statement.executeUpdate()
                    }
                    connection.prepareStatement(
                        "INSERT INTO $decisionTable (scope_key, request_id, decision_status, note) VALUES (?, ?, ?, ?)",
                    ).use { statement ->
                        statement.setString(1, currentScope.get())
                        statement.setString(2, requestId)
                        statement.setString(3, status.name)
                        statement.setString(4, decided.decisionNote)
                        statement.executeUpdate()
                    }
                    connection.commit()
                    decided
                } catch (error: Exception) {
                    runCatching { connection.rollback() }
                    throw error
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }

    override fun recordApplyFailure(
        requestId: String,
        reason: String,
    ): LearningReviewRequest? {
        synchronized(lock) {
            return withConnection { connection ->
                val existing = findById(connection, requestId) ?: return@withConnection null
                val updated =
                    existing.copy(
                        lastApplyFailedAt = Instant.now().toString(),
                        lastApplyFailureReason = reason.trim().ifBlank { "apply failed" },
                    )
                connection.prepareStatement(
                    "UPDATE $queueTable SET payload = ? WHERE scope_key = ? AND request_id = ?",
                ).use { statement ->
                    statement.setString(1, gson.toJson(updated))
                    statement.setString(2, currentScope.get())
                    statement.setString(3, requestId)
                    statement.executeUpdate()
                }
                updated
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
                    CREATE TABLE IF NOT EXISTS $queueTable (
                        id BIGSERIAL PRIMARY KEY,
                        scope_key TEXT NOT NULL,
                        request_id TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        payload TEXT NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE (scope_key, request_id)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS $decisionTable (
                        id BIGSERIAL PRIMARY KEY,
                        scope_key TEXT NOT NULL,
                        request_id TEXT NOT NULL,
                        decision_status TEXT NOT NULL,
                        note TEXT,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    "CREATE INDEX IF NOT EXISTS ${queueTable}_scope_status_idx ON $queueTable (scope_key, status, id)",
                )
                statement.execute(
                    "CREATE INDEX IF NOT EXISTS ${decisionTable}_scope_idx ON $decisionTable (scope_key, id)",
                )
            }
        }
    }

    private fun findById(
        connection: Connection,
        requestId: String,
    ): LearningReviewRequest? {
        connection.prepareStatement(
            "SELECT payload FROM $queueTable WHERE scope_key = ? AND request_id = ? LIMIT 1",
        ).use { statement ->
            statement.setString(1, currentScope.get())
            statement.setString(2, requestId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    return null
                }
                return runCatching {
                    gson.fromJson(rs.getString("payload"), LearningReviewRequest::class.java)
                }.getOrNull()
            }
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

internal object LearningReviewStoreFactory {
    fun create(persistenceConfiguration: PersistenceRuntimeConfiguration): LearningReviewStore {
        val backend = persistenceConfiguration.backend.lowercase()
        return when (backend) {
            "jdbc", "postgres", "postgresql" -> {
                val jdbcUrl =
                    requireNotNull(persistenceConfiguration.jdbcUrl) {
                        "BERTBOT_STATE_JDBC_URL must be set when BERTBOT_STATE_STORE is '$backend'."
                    }
                JdbcLearningReviewStore(
                    jdbcUrl = jdbcUrl,
                    username = persistenceConfiguration.jdbcUser,
                    password = persistenceConfiguration.jdbcPassword,
                    queueTable = persistenceConfiguration.learningReviewJdbcTable,
                    decisionTable = persistenceConfiguration.learningReviewDecisionJdbcTable,
                )
            }
            else -> FileLearningReviewStore(file = File(persistenceConfiguration.learningReviewFilePath))
        }
    }
}

internal fun buildLearningReviewRequest(
    scopeKey: String,
    writeType: LearningReviewWriteType,
    payload: String,
    traceId: String? = null,
): LearningReviewRequest =
    LearningReviewRequest(
        requestId = UUID.randomUUID().toString(),
        createdAt = Instant.now().toString(),
        scopeKey = scopeKey,
        writeType = writeType,
        payload = payload,
        traceId = traceId,
    )
