@file:Suppress("TooManyFunctions")

package com.personalagent.bertbot.app

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.personalagent.bertbot.graph.store.DriverManagerJdbcConnectionProvider
import com.personalagent.bertbot.memory.PersistenceScopeKey
import java.io.File
import java.sql.Connection
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal enum class ScheduledJobState {
    ACTIVE,
    PAUSED,
}

internal enum class ScheduledJobRunTrigger {
    MANUAL,
    SCHEDULED,
}

internal enum class ScheduledJobRunOutcome {
    SUCCESS,
    FAILURE,
}

internal data class ScheduledJob(
    val jobId: String,
    val scopeKey: String,
    val scheduleSeconds: Long,
    val payload: String,
    val state: ScheduledJobState = ScheduledJobState.ACTIVE,
    val createdAt: String,
    val updatedAt: String,
    val nextRunAt: String,
    val lastRunAt: String? = null,
    val lastOutcome: ScheduledJobRunOutcome? = null,
)

internal data class ScheduledJobExecution(
    val runId: String,
    val jobId: String,
    val scopeKey: String,
    val trigger: ScheduledJobRunTrigger,
    val startedAt: String,
    val finishedAt: String,
    val outcome: ScheduledJobRunOutcome,
    val errorSummary: String? = null,
    val traceId: String? = null,
    val previousRunId: String? = null,
)

internal data class ScheduledJobCreateRequest(
    val scheduleSeconds: Long,
    val payload: String,
)

internal data class ScheduledJobUpdateRequest(
    val scheduleSeconds: Long? = null,
    val payload: String? = null,
)

internal data class ScheduledJobRunResult(
    val success: Boolean,
    val errorSummary: String? = null,
    val traceId: String? = null,
)

internal interface ScheduledJobStore {
    fun create(request: ScheduledJobCreateRequest): ScheduledJob

    fun update(
        jobId: String,
        request: ScheduledJobUpdateRequest,
    ): ScheduledJob?

    fun list(limit: Int = 200): List<ScheduledJob>

    fun get(jobId: String): ScheduledJob?

    fun pause(jobId: String): ScheduledJob?

    fun resume(jobId: String): ScheduledJob?

    fun remove(jobId: String): Boolean

    fun recordRun(
        jobId: String,
        runAt: Instant,
        outcome: ScheduledJobRunOutcome,
    ): ScheduledJob?

    fun clear()

    fun <T> withScope(
        scopeKey: String,
        action: () -> T,
    ): T = action()
}

internal interface ScheduledJobExecutionStore {
    fun append(execution: ScheduledJobExecution)

    fun list(
        jobId: String? = null,
        limit: Int = 200,
    ): List<ScheduledJobExecution>

    fun latestRunId(jobId: String): String?

    fun clear()

    fun <T> withScope(
        scopeKey: String,
        action: () -> T,
    ): T = action()
}

internal class FileScheduledJobStore(
    private val file: File,
    private val gson: Gson = Gson(),
) : ScheduledJobStore {
    private val lock = Any()
    private val currentScope = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }

    override fun create(request: ScheduledJobCreateRequest): ScheduledJob {
        val normalized = request.normalized()
        synchronized(lock) {
            val all = loadAll().toMutableList()
            val now = Instant.now()
            val created =
                ScheduledJob(
                    jobId = UUID.randomUUID().toString(),
                    scopeKey = currentScope.get(),
                    scheduleSeconds = normalized.scheduleSeconds,
                    payload = normalized.payload,
                    state = ScheduledJobState.ACTIVE,
                    createdAt = now.toString(),
                    updatedAt = now.toString(),
                    nextRunAt = now.plusSeconds(normalized.scheduleSeconds).toString(),
                )
            all += created
            persist(all)
            return created
        }
    }

    override fun update(
        jobId: String,
        request: ScheduledJobUpdateRequest,
    ): ScheduledJob? {
        val normalizedJobId = jobId.trim()
        if (normalizedJobId.isBlank()) return null
        val normalized = request.normalized()
        synchronized(lock) {
            val all = loadAll().toMutableList()
            val index = all.indexOfFirst { it.jobId == normalizedJobId }
            if (index < 0) return null
            val existing = all[index]
            val now = Instant.now()
            val updated =
                existing.copy(
                    scheduleSeconds = normalized.scheduleSeconds ?: existing.scheduleSeconds,
                    payload = normalized.payload ?: existing.payload,
                    updatedAt = now.toString(),
                    nextRunAt = now.plusSeconds(normalized.scheduleSeconds ?: existing.scheduleSeconds).toString(),
                )
            all[index] = updated
            persist(all)
            return updated
        }
    }

    override fun list(limit: Int): List<ScheduledJob> {
        val bounded = limit.coerceIn(1, 10_000)
        synchronized(lock) {
            return loadAll().takeLast(bounded)
        }
    }

    override fun get(jobId: String): ScheduledJob? {
        val normalizedJobId = jobId.trim()
        if (normalizedJobId.isBlank()) return null
        synchronized(lock) {
            return loadAll().firstOrNull { it.jobId == normalizedJobId }
        }
    }

    override fun pause(jobId: String): ScheduledJob? = transition(jobId, ScheduledJobState.PAUSED)

    override fun resume(jobId: String): ScheduledJob? = transition(jobId, ScheduledJobState.ACTIVE)

    override fun remove(jobId: String): Boolean {
        val normalizedJobId = jobId.trim()
        if (normalizedJobId.isBlank()) return false
        synchronized(lock) {
            val all = loadAll().toMutableList()
            val removed = all.removeIf { it.jobId == normalizedJobId }
            if (removed) persist(all)
            return removed
        }
    }

    override fun recordRun(
        jobId: String,
        runAt: Instant,
        outcome: ScheduledJobRunOutcome,
    ): ScheduledJob? {
        val normalizedJobId = jobId.trim()
        if (normalizedJobId.isBlank()) return null
        synchronized(lock) {
            val all = loadAll().toMutableList()
            val index = all.indexOfFirst { it.jobId == normalizedJobId }
            if (index < 0) return null
            val existing = all[index]
            val updated =
                existing.copy(
                    lastRunAt = runAt.toString(),
                    lastOutcome = outcome,
                    nextRunAt = runAt.plusSeconds(existing.scheduleSeconds).toString(),
                    updatedAt = runAt.toString(),
                )
            all[index] = updated
            persist(all)
            return updated
        }
    }

    override fun clear() {
        synchronized(lock) {
            persist(emptyList())
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

    private fun transition(
        jobId: String,
        targetState: ScheduledJobState,
    ): ScheduledJob? {
        val normalizedJobId = jobId.trim()
        if (normalizedJobId.isBlank()) return null
        synchronized(lock) {
            val all = loadAll().toMutableList()
            val index = all.indexOfFirst { it.jobId == normalizedJobId }
            if (index < 0) return null
            val now = Instant.now()
            val existing = all[index]
            val updated =
                existing.copy(
                    state = targetState,
                    updatedAt = now.toString(),
                    nextRunAt = now.plusSeconds(existing.scheduleSeconds).toString(),
                )
            all[index] = updated
            persist(all)
            return updated
        }
    }

    private fun loadAll(): List<ScheduledJob> {
        val scopedFile = scopedFile()
        if (!scopedFile.exists()) return emptyList()
        val content = scopedFile.readText().trim()
        if (content.isBlank()) return emptyList()

        return runCatching {
            gson.fromJson(content, Array<ScheduledJob>::class.java)
                ?.toList()
                ?.map { it.normalized() }
                ?: emptyList()
        }.getOrElse { error ->
            if (error is JsonSyntaxException) {
                println("Warning: failed to parse scheduled jobs file '${scopedFile.path}'. Returning empty list.")
                emptyList()
            } else {
                throw error
            }
        }
    }

    private fun persist(entries: List<ScheduledJob>) {
        val scopedFile = scopedFile()
        scopedFile.parentFile?.mkdirs()
        scopedFile.writeText(gson.toJson(entries.map { it.normalized() }))
    }

    private fun scopedFile(): File {
        val scope = currentScope.get()
        if (scope == PersistenceScopeKey.defaultScopeKey()) return file

        val parent = file.parentFile ?: File(".")
        val stem = file.nameWithoutExtension
        val ext = file.extension.takeIf { it.isNotBlank() } ?: "json"
        return File(parent, "$stem-$scope.$ext")
    }
}

internal class FileScheduledJobExecutionStore(
    private val file: File,
    private val gson: Gson = Gson(),
) : ScheduledJobExecutionStore {
    private val lock = Any()
    private val currentScope = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }

    override fun append(execution: ScheduledJobExecution) {
        synchronized(lock) {
            val all = loadAll().toMutableList()
            all += execution
            persist(all)
        }
    }

    override fun list(
        jobId: String?,
        limit: Int,
    ): List<ScheduledJobExecution> {
        val bounded = limit.coerceIn(1, 10_000)
        val normalizedJobId = jobId?.trim()?.takeIf { it.isNotBlank() }
        synchronized(lock) {
            val all = loadAll()
            val filtered = if (normalizedJobId == null) all else all.filter { it.jobId == normalizedJobId }
            return filtered.takeLast(bounded)
        }
    }

    override fun latestRunId(jobId: String): String? {
        val normalizedJobId = jobId.trim()
        if (normalizedJobId.isBlank()) return null
        synchronized(lock) {
            return loadAll().lastOrNull { it.jobId == normalizedJobId }?.runId
        }
    }

    override fun clear() {
        synchronized(lock) {
            persist(emptyList())
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

    private fun loadAll(): List<ScheduledJobExecution> {
        val scopedFile = scopedFile()
        if (!scopedFile.exists()) return emptyList()
        return scopedFile
            .readLines()
            .mapNotNull { line ->
                val raw = line.trim()
                if (raw.isBlank()) {
                    null
                } else {
                    runCatching { gson.fromJson(raw, ScheduledJobExecution::class.java) }
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

    private fun persist(entries: List<ScheduledJobExecution>) {
        val scopedFile = scopedFile()
        scopedFile.parentFile?.mkdirs()
        val content = entries.joinToString(separator = "\n") { gson.toJson(it) }
        scopedFile.writeText(content)
    }

    private fun scopedFile(): File {
        val scope = currentScope.get()
        if (scope == PersistenceScopeKey.defaultScopeKey()) return file

        val parent = file.parentFile ?: File(".")
        val stem = file.nameWithoutExtension
        val ext = file.extension.takeIf { it.isNotBlank() } ?: "jsonl"
        return File(parent, "$stem-$scope.$ext")
    }
}

internal class JdbcScheduledJobStore(
    jdbcUrl: String,
    username: String? = null,
    password: String? = null,
    private val tableName: String,
    private val gson: Gson = Gson(),
) : ScheduledJobStore {
    private val connectionProvider = DriverManagerJdbcConnectionProvider(jdbcUrl, username, password)
    private val lock = Any()
    private val currentScope = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }

    init {
        require(tableName.matches(TABLE_NAME_REGEX)) { "tableName must match ${TABLE_NAME_REGEX.pattern}" }
        initializeSchema()
    }

    override fun create(request: ScheduledJobCreateRequest): ScheduledJob {
        val normalized = request.normalized()
        synchronized(lock) {
            val now = Instant.now()
            val created =
                ScheduledJob(
                    jobId = UUID.randomUUID().toString(),
                    scopeKey = currentScope.get(),
                    scheduleSeconds = normalized.scheduleSeconds,
                    payload = normalized.payload,
                    state = ScheduledJobState.ACTIVE,
                    createdAt = now.toString(),
                    updatedAt = now.toString(),
                    nextRunAt = now.plusSeconds(normalized.scheduleSeconds).toString(),
                )
            withConnection { connection ->
                connection.prepareStatement(
                    "INSERT INTO $tableName (scope_key, job_id, state, next_run_at, payload) VALUES (?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, currentScope.get())
                    statement.setString(2, created.jobId)
                    statement.setString(3, created.state.name)
                    statement.setString(4, created.nextRunAt)
                    statement.setString(5, gson.toJson(created))
                    statement.executeUpdate()
                }
            }
            return created
        }
    }

    override fun update(
        jobId: String,
        request: ScheduledJobUpdateRequest,
    ): ScheduledJob? {
        val normalizedJobId = jobId.trim()
        if (normalizedJobId.isBlank()) return null
        val normalized = request.normalized()
        synchronized(lock) {
            return withConnection { connection ->
                val existing = findById(connection, normalizedJobId) ?: return@withConnection null
                val now = Instant.now()
                val updated =
                    existing.copy(
                        scheduleSeconds = normalized.scheduleSeconds ?: existing.scheduleSeconds,
                        payload = normalized.payload ?: existing.payload,
                        updatedAt = now.toString(),
                        nextRunAt = now.plusSeconds(normalized.scheduleSeconds ?: existing.scheduleSeconds).toString(),
                    )
                updatePersisted(connection, updated)
                updated
            }
        }
    }

    override fun list(limit: Int): List<ScheduledJob> {
        val bounded = limit.coerceIn(1, 10_000)
        synchronized(lock) {
            return withConnection { connection ->
                connection.prepareStatement(
                    "SELECT payload FROM $tableName WHERE scope_key = ? ORDER BY id DESC LIMIT ?",
                ).use { statement ->
                    statement.setString(1, currentScope.get())
                    statement.setInt(2, bounded)
                    statement.executeQuery().use { resultSet ->
                        val rows = mutableListOf<ScheduledJob>()
                        while (resultSet.next()) {
                            runCatching {
                                gson.fromJson(resultSet.getString("payload"), ScheduledJob::class.java)
                            }.getOrNull()?.let { rows += it.normalized() }
                        }
                        rows.reversed()
                    }
                }
            }
        }
    }

    override fun get(jobId: String): ScheduledJob? {
        val normalizedJobId = jobId.trim()
        if (normalizedJobId.isBlank()) return null
        synchronized(lock) {
            return withConnection { connection ->
                findById(connection, normalizedJobId)
            }
        }
    }

    override fun pause(jobId: String): ScheduledJob? = transition(jobId, ScheduledJobState.PAUSED)

    override fun resume(jobId: String): ScheduledJob? = transition(jobId, ScheduledJobState.ACTIVE)

    override fun remove(jobId: String): Boolean {
        val normalizedJobId = jobId.trim()
        if (normalizedJobId.isBlank()) return false
        synchronized(lock) {
            return withConnection { connection ->
                connection.prepareStatement("DELETE FROM $tableName WHERE scope_key = ? AND job_id = ?").use { statement ->
                    statement.setString(1, currentScope.get())
                    statement.setString(2, normalizedJobId)
                    statement.executeUpdate() > 0
                }
            }
        }
    }

    override fun recordRun(
        jobId: String,
        runAt: Instant,
        outcome: ScheduledJobRunOutcome,
    ): ScheduledJob? {
        val normalizedJobId = jobId.trim()
        if (normalizedJobId.isBlank()) return null
        synchronized(lock) {
            return withConnection { connection ->
                val existing = findById(connection, normalizedJobId) ?: return@withConnection null
                val updated =
                    existing.copy(
                        lastRunAt = runAt.toString(),
                        lastOutcome = outcome,
                        nextRunAt = runAt.plusSeconds(existing.scheduleSeconds).toString(),
                        updatedAt = runAt.toString(),
                    )
                updatePersisted(connection, updated)
                updated
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

    private fun transition(
        jobId: String,
        state: ScheduledJobState,
    ): ScheduledJob? {
        val normalizedJobId = jobId.trim()
        if (normalizedJobId.isBlank()) return null
        return withConnection { connection ->
            val existing = findById(connection, normalizedJobId) ?: return@withConnection null
            val now = Instant.now()
            val updated =
                existing.copy(
                    state = state,
                    updatedAt = now.toString(),
                    nextRunAt = now.plusSeconds(existing.scheduleSeconds).toString(),
                )
            updatePersisted(connection, updated)
            updated
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
                        job_id TEXT NOT NULL,
                        state TEXT NOT NULL,
                        next_run_at TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE (scope_key, job_id)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    "CREATE INDEX IF NOT EXISTS ${tableName}_scope_next_run_idx ON $tableName (scope_key, next_run_at, id)",
                )
            }
        }
    }

    private fun findById(
        connection: Connection,
        jobId: String,
    ): ScheduledJob? {
        connection.prepareStatement("SELECT payload FROM $tableName WHERE scope_key = ? AND job_id = ? LIMIT 1").use { statement ->
            statement.setString(1, currentScope.get())
            statement.setString(2, jobId)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) return null
                return runCatching {
                    gson.fromJson(resultSet.getString("payload"), ScheduledJob::class.java)
                }.getOrNull()?.normalized()
            }
        }
    }

    private fun updatePersisted(
        connection: Connection,
        updated: ScheduledJob,
    ) {
        connection.prepareStatement(
            "UPDATE $tableName SET state = ?, next_run_at = ?, payload = ? WHERE scope_key = ? AND job_id = ?",
        ).use { statement ->
            statement.setString(1, updated.state.name)
            statement.setString(2, updated.nextRunAt)
            statement.setString(3, gson.toJson(updated.normalized()))
            statement.setString(4, currentScope.get())
            statement.setString(5, updated.jobId)
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

internal class JdbcScheduledJobExecutionStore(
    jdbcUrl: String,
    username: String? = null,
    password: String? = null,
    private val tableName: String,
    private val gson: Gson = Gson(),
) : ScheduledJobExecutionStore {
    private val connectionProvider = DriverManagerJdbcConnectionProvider(jdbcUrl, username, password)
    private val lock = Any()
    private val currentScope = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }

    init {
        require(tableName.matches(TABLE_NAME_REGEX)) { "tableName must match ${TABLE_NAME_REGEX.pattern}" }
        initializeSchema()
    }

    override fun append(execution: ScheduledJobExecution) {
        synchronized(lock) {
            withConnection { connection ->
                connection.prepareStatement(
                    "INSERT INTO $tableName (scope_key, job_id, run_id, payload) VALUES (?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, currentScope.get())
                    statement.setString(2, execution.jobId)
                    statement.setString(3, execution.runId)
                    statement.setString(4, gson.toJson(execution))
                    statement.executeUpdate()
                }
            }
        }
    }

    override fun list(
        jobId: String?,
        limit: Int,
    ): List<ScheduledJobExecution> {
        val bounded = limit.coerceIn(1, 10_000)
        val normalizedJobId = jobId?.trim()?.takeIf { it.isNotBlank() }
        synchronized(lock) {
            return withConnection { connection ->
                val sql =
                    if (normalizedJobId == null) {
                        "SELECT payload FROM $tableName WHERE scope_key = ? ORDER BY id DESC LIMIT ?"
                    } else {
                        "SELECT payload FROM $tableName WHERE scope_key = ? AND job_id = ? ORDER BY id DESC LIMIT ?"
                    }
                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, currentScope.get())
                    if (normalizedJobId == null) {
                        statement.setInt(2, bounded)
                    } else {
                        statement.setString(2, normalizedJobId)
                        statement.setInt(3, bounded)
                    }
                    statement.executeQuery().use { resultSet ->
                        val rows = mutableListOf<ScheduledJobExecution>()
                        while (resultSet.next()) {
                            runCatching {
                                gson.fromJson(resultSet.getString("payload"), ScheduledJobExecution::class.java)
                            }.getOrNull()?.let { rows += it }
                        }
                        rows.reversed()
                    }
                }
            }
        }
    }

    override fun latestRunId(jobId: String): String? {
        val normalizedJobId = jobId.trim()
        if (normalizedJobId.isBlank()) return null
        synchronized(lock) {
            return withConnection { connection ->
                connection.prepareStatement(
                    "SELECT run_id FROM $tableName WHERE scope_key = ? AND job_id = ? ORDER BY id DESC LIMIT 1",
                ).use { statement ->
                    statement.setString(1, currentScope.get())
                    statement.setString(2, normalizedJobId)
                    statement.executeQuery().use { resultSet ->
                        if (!resultSet.next()) null else resultSet.getString("run_id")
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
                        job_id TEXT NOT NULL,
                        run_id TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE (scope_key, run_id)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    "CREATE INDEX IF NOT EXISTS ${tableName}_scope_job_idx ON $tableName (scope_key, job_id, id)",
                )
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

internal object ScheduledJobStoreFactory {
    fun create(
        persistenceConfiguration: PersistenceRuntimeConfiguration,
    ): Pair<ScheduledJobStore, ScheduledJobExecutionStore> {
        val backend = persistenceConfiguration.backend.lowercase()
        return when (backend) {
            "jdbc", "postgres", "postgresql" -> {
                val jdbcUrl =
                    requireNotNull(persistenceConfiguration.jdbcUrl) {
                        "BERTBOT_STATE_JDBC_URL must be set when BERTBOT_STATE_STORE is '$backend'."
                    }
                JdbcScheduledJobStore(
                    jdbcUrl = jdbcUrl,
                    username = persistenceConfiguration.jdbcUser,
                    password = persistenceConfiguration.jdbcPassword,
                    tableName = persistenceConfiguration.scheduledJobJdbcTable,
                ) to
                    JdbcScheduledJobExecutionStore(
                        jdbcUrl = jdbcUrl,
                        username = persistenceConfiguration.jdbcUser,
                        password = persistenceConfiguration.jdbcPassword,
                        tableName = persistenceConfiguration.scheduledJobExecutionJdbcTable,
                    )
            }
            else -> {
                FileScheduledJobStore(File(persistenceConfiguration.scheduledJobFilePath)) to
                    FileScheduledJobExecutionStore(File(persistenceConfiguration.scheduledJobExecutionFilePath))
            }
        }
    }
}

internal class ScheduledJobService(
    private val jobStore: ScheduledJobStore,
    private val executionStore: ScheduledJobExecutionStore,
    private val runJob: (ScheduledJob) -> ScheduledJobRunResult,
    private val configuration: ScheduledJobsRuntimeConfiguration,
    private val clock: () -> Instant = { Instant.now() },
) : AutoCloseable {
    private val lock = Any()
    private var scheduler: ScheduledExecutorService? = null

    fun create(
        scopeKey: String,
        request: ScheduledJobCreateRequest,
    ): ScheduledJob =
        jobStore.withScope(scopeKey) {
            executionStore.withScope(scopeKey) {
                jobStore.create(request)
            }
        }

    fun update(
        scopeKey: String,
        jobId: String,
        request: ScheduledJobUpdateRequest,
    ): ScheduledJob? =
        jobStore.withScope(scopeKey) {
            executionStore.withScope(scopeKey) {
                jobStore.update(jobId, request)
            }
        }

    fun list(
        scopeKey: String,
        limit: Int = 200,
    ): List<ScheduledJob> =
        jobStore.withScope(scopeKey) {
            executionStore.withScope(scopeKey) {
                jobStore.list(limit)
            }
        }

    fun pause(
        scopeKey: String,
        jobId: String,
    ): ScheduledJob? =
        jobStore.withScope(scopeKey) {
            executionStore.withScope(scopeKey) {
                jobStore.pause(jobId)
            }
        }

    fun resume(
        scopeKey: String,
        jobId: String,
    ): ScheduledJob? =
        jobStore.withScope(scopeKey) {
            executionStore.withScope(scopeKey) {
                jobStore.resume(jobId)
            }
        }

    fun remove(
        scopeKey: String,
        jobId: String,
    ): Boolean =
        jobStore.withScope(scopeKey) {
            executionStore.withScope(scopeKey) {
                jobStore.remove(jobId)
            }
        }

    fun runNow(
        scopeKey: String,
        jobId: String,
    ): ScheduledJobExecution? =
        jobStore.withScope(scopeKey) {
            executionStore.withScope(scopeKey) {
                val job = jobStore.get(jobId) ?: return@withScope null
                execute(job, ScheduledJobRunTrigger.MANUAL)
            }
        }

    fun listHistory(
        scopeKey: String,
        jobId: String? = null,
        limit: Int = 200,
    ): List<ScheduledJobExecution> =
        executionStore.withScope(scopeKey) {
            executionStore.list(jobId = jobId, limit = limit)
        }

    fun start() {
        if (!configuration.enabled) {
            return
        }

        synchronized(lock) {
            if (scheduler != null) return
            scheduler =
                Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "bertbot-scheduled-jobs")
                }.also { executor ->
                    executor.scheduleWithFixedDelay(
                        { tickAllScopes() },
                        configuration.initialDelaySeconds,
                        configuration.pollIntervalSeconds,
                        TimeUnit.SECONDS,
                    )
                }
        }
    }

    override fun close() {
        synchronized(lock) {
            scheduler?.shutdownNow()
            scheduler = null
        }
    }

    private fun tickAllScopes() {
        val scopes = collectKnownScopes()
        scopes.forEach { scopeKey ->
            jobStore.withScope(scopeKey) {
                executionStore.withScope(scopeKey) {
                    tickScope()
                }
            }
        }
    }

    private fun collectKnownScopes(): List<String> {
        val globalJobs = jobStore.withScope(PersistenceScopeKey.defaultScopeKey()) { jobStore.list(limit = 10_000) }
        val scoped = globalJobs.map { it.scopeKey }.toMutableSet()
        scoped += PersistenceScopeKey.defaultScopeKey()
        return scoped.toList()
    }

    private fun tickScope() {
        val now = clock()
        val dueJobs =
            jobStore.list(limit = 10_000).filter { job ->
                job.state == ScheduledJobState.ACTIVE && runCatching { Instant.parse(job.nextRunAt) }.getOrNull()?.let { it <= now } == true
            }

        dueJobs.forEach { job ->
            execute(job, ScheduledJobRunTrigger.SCHEDULED)
        }
    }

    private fun execute(
        job: ScheduledJob,
        trigger: ScheduledJobRunTrigger,
    ): ScheduledJobExecution {
        val started = clock()
        val previousRunId = executionStore.latestRunId(job.jobId)
        val result = runCatching { runJob(job) }.getOrElse { error -> ScheduledJobRunResult(success = false, errorSummary = error.message) }
        val outcome = if (result.success) ScheduledJobRunOutcome.SUCCESS else ScheduledJobRunOutcome.FAILURE
        val finished = clock()
        val execution =
            ScheduledJobExecution(
                runId = UUID.randomUUID().toString(),
                jobId = job.jobId,
                scopeKey = job.scopeKey,
                trigger = trigger,
                startedAt = started.toString(),
                finishedAt = finished.toString(),
                outcome = outcome,
                errorSummary = result.errorSummary?.trim()?.ifBlank { null },
                traceId = result.traceId,
                previousRunId = previousRunId,
            )
        executionStore.append(execution)
        jobStore.recordRun(jobId = job.jobId, runAt = finished, outcome = outcome)
        return execution
    }
}

internal fun runScheduledJobPayload(
    runtime: BertBotRuntime,
    scopeKey: String,
    job: ScheduledJob,
): ScheduledJobRunResult {
    val userMessage = job.payload
    if (userMessage.isBlank()) {
        return ScheduledJobRunResult(success = false, errorSummary = "job payload must not be blank")
    }

    return runCatching {
        val response =
            runtime.respondTo(
                userMessage = userMessage,
                emitFallbackMessage = false,
                persistenceScopeKey = scopeKey,
                traceCorrelationId = "scheduled-job-${job.jobId}",
            )
        if (response.isNullOrBlank()) {
            ScheduledJobRunResult(success = false, errorSummary = "runtime returned empty response")
        } else {
            ScheduledJobRunResult(success = true)
        }
    }.getOrElse { error ->
        ScheduledJobRunResult(
            success = false,
            errorSummary = error.message ?: "scheduled job execution failed",
        )
    }
}

private fun ScheduledJobCreateRequest.normalized(): ScheduledJobCreateRequest {
    val normalizedScheduleSeconds = scheduleSeconds.coerceAtLeast(30)
    val normalizedPayload = payload.trim()
    require(normalizedPayload.isNotBlank()) { "payload must not be blank" }
    return copy(scheduleSeconds = normalizedScheduleSeconds, payload = normalizedPayload)
}

private fun ScheduledJobUpdateRequest.normalized(): ScheduledJobUpdateRequest {
    val normalizedPayload = payload?.trim()?.ifBlank { null }
    val normalizedScheduleSeconds = scheduleSeconds?.coerceAtLeast(30)
    return copy(scheduleSeconds = normalizedScheduleSeconds, payload = normalizedPayload)
}

private fun ScheduledJob.normalized(): ScheduledJob =
    copy(
        jobId = jobId.trim(),
        scopeKey = scopeKey.trim().ifBlank { PersistenceScopeKey.defaultScopeKey() },
        payload = payload.trim(),
        scheduleSeconds = scheduleSeconds.coerceAtLeast(30),
        nextRunAt = normalizeIso(nextRunAt),
        createdAt = normalizeIso(createdAt),
        updatedAt = normalizeIso(updatedAt),
        lastRunAt = lastRunAt?.let(::normalizeIso),
    )

private fun normalizeIso(value: String): String {
    return runCatching {
        Instant.parse(value).truncatedTo(ChronoUnit.MILLIS).toString()
    }.getOrElse {
        Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()
    }
}
