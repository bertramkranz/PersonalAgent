package com.personalagent.bertbot.app

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.personalagent.bertbot.graph.store.DriverManagerJdbcConnectionProvider
import com.personalagent.bertbot.memory.PersistenceScopeKey
import java.io.File
import java.sql.Connection
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal data class LearningProposalSignalState(
    val dedupeKey: String,
    val scopeKey: String,
    val count: Int,
    val lastSeenAt: String,
    val lastQueuedAt: String? = null,
)

internal interface LearningProposalSignalStore {
    fun upsert(
        dedupeKey: String,
        incrementBy: Int = 1,
    ): LearningProposalSignalState

    fun list(limit: Int = 500): List<LearningProposalSignalState>

    fun markQueued(
        dedupeKey: String,
        queuedAt: Instant,
    ): LearningProposalSignalState?

    fun clear()

    fun <T> withScope(
        scopeKey: String,
        action: () -> T,
    ): T = action()
}

internal class FileLearningProposalSignalStore(
    private val file: File,
    private val gson: Gson = Gson(),
) : LearningProposalSignalStore {
    private val lock = Any()
    private val currentScope = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }

    override fun upsert(
        dedupeKey: String,
        incrementBy: Int,
    ): LearningProposalSignalState {
        val normalizedKey = dedupeKey.trim().lowercase().ifBlank { "signal:unknown" }
        val increment = incrementBy.coerceAtLeast(1)
        synchronized(lock) {
            val all = loadAll().toMutableList()
            val now = Instant.now().toString()
            val index = all.indexOfFirst { it.dedupeKey == normalizedKey }
            val updated =
                if (index < 0) {
                    LearningProposalSignalState(
                        dedupeKey = normalizedKey,
                        scopeKey = currentScope.get(),
                        count = increment,
                        lastSeenAt = now,
                    )
                } else {
                    val existing = all[index]
                    existing.copy(count = existing.count + increment, lastSeenAt = now)
                }
            if (index < 0) {
                all += updated
            } else {
                all[index] = updated
            }
            persist(all)
            return updated
        }
    }

    override fun list(limit: Int): List<LearningProposalSignalState> {
        val bounded = limit.coerceIn(1, 10_000)
        synchronized(lock) {
            return loadAll().takeLast(bounded)
        }
    }

    override fun markQueued(
        dedupeKey: String,
        queuedAt: Instant,
    ): LearningProposalSignalState? {
        val normalizedKey = dedupeKey.trim().lowercase()
        if (normalizedKey.isBlank()) return null
        synchronized(lock) {
            val all = loadAll().toMutableList()
            val index = all.indexOfFirst { it.dedupeKey == normalizedKey }
            if (index < 0) return null
            val updated = all[index].copy(lastQueuedAt = queuedAt.toString(), lastSeenAt = queuedAt.toString())
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

    private fun loadAll(): List<LearningProposalSignalState> {
        val scopedFile = scopedFile()
        if (!scopedFile.exists()) return emptyList()
        val content = scopedFile.readText().trim()
        if (content.isBlank()) return emptyList()
        return runCatching {
            gson.fromJson(content, Array<LearningProposalSignalState>::class.java)
                ?.toList()
                ?: emptyList()
        }.getOrElse { error ->
            if (error is JsonSyntaxException) {
                println("Warning: failed to parse learning proposal signal file '${scopedFile.path}'. Returning empty list.")
                emptyList()
            } else {
                throw error
            }
        }
    }

    private fun persist(entries: List<LearningProposalSignalState>) {
        val scopedFile = scopedFile()
        scopedFile.parentFile?.mkdirs()
        scopedFile.writeText(gson.toJson(entries))
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

internal class JdbcLearningProposalSignalStore(
    jdbcUrl: String,
    username: String? = null,
    password: String? = null,
    private val tableName: String,
) : LearningProposalSignalStore {
    private val connectionProvider = DriverManagerJdbcConnectionProvider(jdbcUrl, username, password)
    private val lock = Any()
    private val currentScope = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }

    init {
        require(tableName.matches(TABLE_NAME_REGEX)) { "tableName must match ${TABLE_NAME_REGEX.pattern}" }
        initializeSchema()
    }

    override fun upsert(
        dedupeKey: String,
        incrementBy: Int,
    ): LearningProposalSignalState {
        val normalizedKey = dedupeKey.trim().lowercase().ifBlank { "signal:unknown" }
        val increment = incrementBy.coerceAtLeast(1)
        synchronized(lock) {
            return withConnection { connection ->
                val existing = find(connection, normalizedKey)
                val now = Instant.now().toString()
                val updated =
                    if (existing == null) {
                        LearningProposalSignalState(
                            dedupeKey = normalizedKey,
                            scopeKey = currentScope.get(),
                            count = increment,
                            lastSeenAt = now,
                        )
                    } else {
                        existing.copy(count = existing.count + increment, lastSeenAt = now)
                    }
                persist(connection, updated)
                updated
            }
        }
    }

    override fun list(limit: Int): List<LearningProposalSignalState> {
        val bounded = limit.coerceIn(1, 10_000)
        synchronized(lock) {
            return withConnection { connection ->
                connection.prepareStatement(
                    "SELECT dedupe_key, count_value, last_seen_at, last_queued_at FROM $tableName WHERE scope_key = ? ORDER BY id DESC LIMIT ?",
                ).use { statement ->
                    statement.setString(1, currentScope.get())
                    statement.setInt(2, bounded)
                    statement.executeQuery().use { rs ->
                        val rows = mutableListOf<LearningProposalSignalState>()
                        while (rs.next()) {
                            rows +=
                                LearningProposalSignalState(
                                    dedupeKey = rs.getString("dedupe_key"),
                                    scopeKey = currentScope.get(),
                                    count = rs.getInt("count_value"),
                                    lastSeenAt = rs.getString("last_seen_at"),
                                    lastQueuedAt = rs.getString("last_queued_at"),
                                )
                        }
                        rows.reversed()
                    }
                }
            }
        }
    }

    override fun markQueued(
        dedupeKey: String,
        queuedAt: Instant,
    ): LearningProposalSignalState? {
        val normalizedKey = dedupeKey.trim().lowercase()
        if (normalizedKey.isBlank()) return null
        synchronized(lock) {
            return withConnection { connection ->
                val existing = find(connection, normalizedKey) ?: return@withConnection null
                val updated = existing.copy(lastQueuedAt = queuedAt.toString(), lastSeenAt = queuedAt.toString())
                persist(connection, updated)
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

    private fun initializeSchema() {
        withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS $tableName (
                        id BIGSERIAL PRIMARY KEY,
                        scope_key TEXT NOT NULL,
                        dedupe_key TEXT NOT NULL,
                        count_value INTEGER NOT NULL,
                        last_seen_at TEXT NOT NULL,
                        last_queued_at TEXT,
                        UNIQUE (scope_key, dedupe_key)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    "CREATE INDEX IF NOT EXISTS ${tableName}_scope_seen_idx ON $tableName (scope_key, last_seen_at, id)",
                )
            }
        }
    }

    private fun find(
        connection: Connection,
        dedupeKey: String,
    ): LearningProposalSignalState? {
        connection.prepareStatement(
            "SELECT count_value, last_seen_at, last_queued_at FROM $tableName WHERE scope_key = ? AND dedupe_key = ? LIMIT 1",
        ).use { statement ->
            statement.setString(1, currentScope.get())
            statement.setString(2, dedupeKey)
            statement.executeQuery().use { rs ->
                if (!rs.next()) return null
                return LearningProposalSignalState(
                    dedupeKey = dedupeKey,
                    scopeKey = currentScope.get(),
                    count = rs.getInt("count_value"),
                    lastSeenAt = rs.getString("last_seen_at"),
                    lastQueuedAt = rs.getString("last_queued_at"),
                )
            }
        }
    }

    private fun persist(
        connection: Connection,
        state: LearningProposalSignalState,
    ) {
        connection.prepareStatement(
            """
            MERGE INTO $tableName (scope_key, dedupe_key, count_value, last_seen_at, last_queued_at)
            KEY(scope_key, dedupe_key)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, currentScope.get())
            statement.setString(2, state.dedupeKey)
            statement.setInt(3, state.count)
            statement.setString(4, state.lastSeenAt)
            statement.setString(5, state.lastQueuedAt)
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

internal object LearningProposalSignalStoreFactory {
    fun create(persistenceConfiguration: PersistenceRuntimeConfiguration): LearningProposalSignalStore {
        val backend = persistenceConfiguration.backend.lowercase()
        return when (backend) {
            "jdbc", "postgres", "postgresql" -> {
                val jdbcUrl =
                    requireNotNull(persistenceConfiguration.jdbcUrl) {
                        "BERTBOT_STATE_JDBC_URL must be set when BERTBOT_STATE_STORE is '$backend'."
                    }
                JdbcLearningProposalSignalStore(
                    jdbcUrl = jdbcUrl,
                    username = persistenceConfiguration.jdbcUser,
                    password = persistenceConfiguration.jdbcPassword,
                    tableName = persistenceConfiguration.learningProposalSignalJdbcTable,
                )
            }
            else -> FileLearningProposalSignalStore(file = File(persistenceConfiguration.learningProposalSignalFilePath))
        }
    }
}

internal class LearningProposalLoopService(
    private val signalStore: LearningProposalSignalStore,
    private val learningReviewStore: LearningReviewStore,
    private val configuration: LearningProposalRuntimeConfiguration,
    private val now: () -> Instant = { Instant.now() },
) : AutoCloseable {
    private val lock = Any()
    private var scheduler: ScheduledExecutorService? = null

    fun start() {
        if (!configuration.enabled) {
            return
        }
        synchronized(lock) {
            if (scheduler != null) return
            scheduler =
                Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "bertbot-learning-proposal-loop")
                }.also { executor ->
                    executor.scheduleWithFixedDelay(
                        { tickDefaultScope() },
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

    fun recordSignal(
        scopeKey: String,
        dedupeKey: String,
        incrementBy: Int = 1,
    ): LearningProposalSignalState =
        signalStore.withScope(scopeKey) {
            signalStore.upsert(dedupeKey = dedupeKey, incrementBy = incrementBy)
        }

    fun tick(scopeKey: String): Int =
        signalStore.withScope(scopeKey) {
            learningReviewStore.withScope(scopeKey) {
                generateProposalsForScope(scopeKey)
            }
        }

    private fun tickDefaultScope() {
        tick(PersistenceScopeKey.defaultScopeKey())
    }

    private fun generateProposalsForScope(scopeKey: String): Int {
        val entries = signalStore.list(limit = 10_000).sortedByDescending { it.count }
        if (entries.isEmpty()) return 0

        var produced = 0
        val cutoff = now().minusSeconds(configuration.cooldownMinutes * 60)
        entries.forEach { signal ->
            if (produced >= configuration.maxBatchSize) return@forEach
            val lastQueuedAt = signal.lastQueuedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val isCoolingDown = lastQueuedAt != null && lastQueuedAt.isAfter(cutoff)
            if (isCoolingDown) {
                return@forEach
            }
            if (signal.count < 2) {
                return@forEach
            }

            val payload =
                gson.toJson(
                    mapOf(
                        "reason" to "background_learning_proposal dedupeKey=${signal.dedupeKey} count=${signal.count}",
                    ),
                )
            learningReviewStore.enqueue(
                buildLearningReviewRequest(
                    scopeKey = scopeKey,
                    writeType = LearningReviewWriteType.SKILL,
                    payload = payload,
                    traceId = "learning-proposal-${signal.dedupeKey}",
                ),
            )
            signalStore.markQueued(signal.dedupeKey, now())
            produced += 1
        }

        return produced
    }

    private companion object {
        private val gson = Gson()
    }
}
