package com.personalagent.bertbot.app

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.personalagent.bertbot.graph.store.DriverManagerJdbcConnectionProvider
import com.personalagent.bertbot.memory.PersistenceScopeKey
import java.io.File
import java.sql.Connection
import java.time.Instant

internal data class RoutingTelemetrySummary(
    val routeKey: String,
    val scopeKey: String,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val lastSuccessAt: String? = null,
    val lastFailureAt: String? = null,
    val lastUpdatedAt: String,
)

internal data class RoutingHint(
    val routeKey: String,
    val score: Double,
    val confidence: Double,
    val successBias: Double,
    val failureDampening: Double,
    val sampleSize: Int,
    val reason: String,
)

internal data class RoutingHintRuntimeConfiguration(
    val enabled: Boolean = DEFAULT_ROUTING_HINTS_ENABLED,
    val minSamplesPerRoute: Int = DEFAULT_ROUTING_HINTS_MIN_SAMPLES,
    val maxInfluence: Double = DEFAULT_ROUTING_HINTS_MAX_INFLUENCE,
    val recencyHalfLifeHours: Int = DEFAULT_ROUTING_HINTS_RECENCY_HALF_LIFE_HOURS,
)

internal interface RoutingTelemetryStore {
    fun list(limit: Int = 500): List<RoutingTelemetrySummary>

    fun recordOutcome(
        routeKey: String,
        success: Boolean,
    )

    fun clear()

    fun <T> withScope(
        scopeKey: String,
        action: () -> T,
    ): T = action()
}

internal class FileRoutingTelemetryStore(
    private val file: File,
    private val gson: Gson = Gson(),
) : RoutingTelemetryStore {
    private val lock = Any()
    private val currentScope = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }
    private val legacyScopeAlias = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }

    override fun list(limit: Int): List<RoutingTelemetrySummary> {
        val bounded = limit.coerceIn(1, 10_000)
        synchronized(lock) {
            return loadForCurrentScope().takeLast(bounded)
        }
    }

    override fun recordOutcome(
        routeKey: String,
        success: Boolean,
    ) {
        val normalizedRouteKey = routeKey.trim().ifBlank { return }
        synchronized(lock) {
            val all = loadForCurrentScope().toMutableList()
            val index = all.indexOfFirst { it.routeKey == normalizedRouteKey }
            val now = Instant.now().toString()
            val updated =
                if (index >= 0) {
                    val existing = all[index]
                    existing.copy(
                        successCount = if (success) existing.successCount + 1 else existing.successCount,
                        failureCount = if (success) existing.failureCount else existing.failureCount + 1,
                        lastSuccessAt = if (success) now else existing.lastSuccessAt,
                        lastFailureAt = if (success) existing.lastFailureAt else now,
                        lastUpdatedAt = now,
                    )
                } else {
                    RoutingTelemetrySummary(
                        routeKey = normalizedRouteKey,
                        scopeKey = currentScope.get(),
                        successCount = if (success) 1 else 0,
                        failureCount = if (success) 0 else 1,
                        lastSuccessAt = if (success) now else null,
                        lastFailureAt = if (success) null else now,
                        lastUpdatedAt = now,
                    )
                }

            if (index >= 0) {
                all[index] = updated
            } else {
                all += updated
            }
            persistForCurrentScope(all)
        }
    }

    override fun clear() {
        synchronized(lock) {
            persistForCurrentScope(emptyList())
        }
    }

    override fun <T> withScope(
        scopeKey: String,
        action: () -> T,
    ): T {
        val previous = currentScope.get()
        val previousLegacyAlias = legacyScopeAlias.get()
        currentScope.set(PersistenceScopeKey.normalizeForFile(scopeKey))
        legacyScopeAlias.set(PersistenceScopeKey.legacyFileAlias(scopeKey))
        return try {
            action()
        } finally {
            currentScope.set(previous)
            legacyScopeAlias.set(previousLegacyAlias)
        }
    }

    private fun loadForCurrentScope(): List<RoutingTelemetrySummary> {
        val scopedFile = scopedFile()
        val legacyFile = legacyScopedFile()
        val existingFile = if (scopedFile.exists()) scopedFile else legacyFile
        if (!existingFile.exists()) return emptyList()
        if (existingFile == legacyFile && legacyFile != scopedFile) {
            println("Warning: routing telemetry store loaded legacy scoped file '${legacyFile.path}' because normalized scoped file '${scopedFile.path}' was not found.")
        }

        val content = existingFile.readText().trim()
        if (content.isBlank()) return emptyList()

        return runCatching {
            gson.fromJson(content, Array<RoutingTelemetrySummary>::class.java)
                ?.toList()
                ?.map { it.copy(routeKey = it.routeKey.trim(), scopeKey = it.scopeKey.trim()) }
                ?: emptyList()
        }.getOrElse { error ->
            if (error is JsonSyntaxException) {
                println("Warning: failed to parse routing telemetry file '${existingFile.path}'. Returning empty list.")
                emptyList()
            } else {
                throw error
            }
        }
    }

    private fun persistForCurrentScope(entries: List<RoutingTelemetrySummary>) {
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

    private fun legacyScopedFile(): File {
        val scope = legacyScopeAlias.get()
        if (scope == PersistenceScopeKey.defaultScopeKey()) return file

        val parent = file.parentFile ?: File(".")
        val stem = file.nameWithoutExtension
        val ext = file.extension.takeIf { it.isNotBlank() } ?: "json"
        return File(parent, "$stem-$scope.$ext")
    }
}

internal class JdbcRoutingTelemetryStore(
    jdbcUrl: String,
    username: String? = null,
    password: String? = null,
    private val tableName: String,
) : RoutingTelemetryStore {
    private val connectionProvider = DriverManagerJdbcConnectionProvider(jdbcUrl, username, password)
    private val lock = Any()
    private val currentScope = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }
    private val legacyScopeAlias = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }

    init {
        require(tableName.matches(TABLE_NAME_REGEX)) { "tableName must match ${TABLE_NAME_REGEX.pattern}" }
        initializeSchema()
    }

    override fun list(limit: Int): List<RoutingTelemetrySummary> {
        val bounded = limit.coerceIn(1, 10_000)
        synchronized(lock) {
            return withConnection { connection ->
                val scope = currentScope.get()
                val primary = queryByScope(connection, scope, bounded)
                if (primary.isNotEmpty()) {
                    primary
                } else {
                    val legacyScope = legacyScopeAlias.get()
                    queryByScope(connection, legacyScope, bounded)
                }
            }
        }
    }

    override fun recordOutcome(
        routeKey: String,
        success: Boolean,
    ) {
        val normalizedRouteKey = routeKey.trim().ifBlank { return }
        synchronized(lock) {
            withConnection { connection ->
                connection.autoCommit = false
                try {
                    val existing = findByRoute(connection, normalizedRouteKey)
                    val now = Instant.now().toString()
                    if (existing == null) {
                        val created =
                            RoutingTelemetrySummary(
                                routeKey = normalizedRouteKey,
                                scopeKey = currentScope.get(),
                                successCount = if (success) 1 else 0,
                                failureCount = if (success) 0 else 1,
                                lastSuccessAt = if (success) now else null,
                                lastFailureAt = if (success) null else now,
                                lastUpdatedAt = now,
                            )
                        insertSummary(connection, created)
                    } else {
                        val updated =
                            existing.copy(
                                successCount = if (success) existing.successCount + 1 else existing.successCount,
                                failureCount = if (success) existing.failureCount else existing.failureCount + 1,
                                lastSuccessAt = if (success) now else existing.lastSuccessAt,
                                lastFailureAt = if (success) existing.lastFailureAt else now,
                                lastUpdatedAt = now,
                            )
                        updateSummary(connection, updated)
                    }
                    connection.commit()
                } catch (error: Exception) {
                    runCatching { connection.rollback() }
                    throw error
                } finally {
                    connection.autoCommit = true
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
        val previousLegacyAlias = legacyScopeAlias.get()
        currentScope.set(PersistenceScopeKey.normalizeForJdbc(scopeKey))
        legacyScopeAlias.set(PersistenceScopeKey.legacyJdbcAlias(scopeKey))
        return try {
            action()
        } finally {
            currentScope.set(previous)
            legacyScopeAlias.set(previousLegacyAlias)
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
                        route_key TEXT NOT NULL,
                        success_count INTEGER NOT NULL,
                        failure_count INTEGER NOT NULL,
                        last_success_at TEXT,
                        last_failure_at TEXT,
                        last_updated_at TEXT NOT NULL,
                        UNIQUE (scope_key, route_key)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    "CREATE INDEX IF NOT EXISTS ${tableName}_scope_route_idx ON $tableName (scope_key, route_key)",
                )
            }
        }
    }

    private fun queryByScope(
        connection: Connection,
        scopeKey: String,
        limit: Int,
    ): List<RoutingTelemetrySummary> {
        val sql =
            """
            SELECT route_key, scope_key, success_count, failure_count, last_success_at, last_failure_at, last_updated_at
            FROM $tableName
            WHERE scope_key = ?
            ORDER BY id ASC
            LIMIT ?
            """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, scopeKey)
            statement.setInt(2, limit)
            statement.executeQuery().use { rs ->
                val rows = mutableListOf<RoutingTelemetrySummary>()
                while (rs.next()) {
                    rows +=
                        RoutingTelemetrySummary(
                            routeKey = rs.getString("route_key"),
                            scopeKey = rs.getString("scope_key"),
                            successCount = rs.getInt("success_count"),
                            failureCount = rs.getInt("failure_count"),
                            lastSuccessAt = rs.getString("last_success_at"),
                            lastFailureAt = rs.getString("last_failure_at"),
                            lastUpdatedAt = rs.getString("last_updated_at"),
                        )
                }
                return rows
            }
        }
    }

    private fun findByRoute(
        connection: Connection,
        routeKey: String,
    ): RoutingTelemetrySummary? {
        val sql =
            """
            SELECT route_key, scope_key, success_count, failure_count, last_success_at, last_failure_at, last_updated_at
            FROM $tableName
            WHERE scope_key = ? AND route_key = ?
            LIMIT 1
            """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, currentScope.get())
            statement.setString(2, routeKey)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    return null
                }
                return RoutingTelemetrySummary(
                    routeKey = rs.getString("route_key"),
                    scopeKey = rs.getString("scope_key"),
                    successCount = rs.getInt("success_count"),
                    failureCount = rs.getInt("failure_count"),
                    lastSuccessAt = rs.getString("last_success_at"),
                    lastFailureAt = rs.getString("last_failure_at"),
                    lastUpdatedAt = rs.getString("last_updated_at"),
                )
            }
        }
    }

    private fun insertSummary(
        connection: Connection,
        summary: RoutingTelemetrySummary,
    ) {
        val sql =
            """
            INSERT INTO $tableName (
                scope_key,
                route_key,
                success_count,
                failure_count,
                last_success_at,
                last_failure_at,
                last_updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, summary.scopeKey)
            statement.setString(2, summary.routeKey)
            statement.setInt(3, summary.successCount)
            statement.setInt(4, summary.failureCount)
            statement.setString(5, summary.lastSuccessAt)
            statement.setString(6, summary.lastFailureAt)
            statement.setString(7, summary.lastUpdatedAt)
            statement.executeUpdate()
        }
    }

    private fun updateSummary(
        connection: Connection,
        summary: RoutingTelemetrySummary,
    ) {
        val sql =
            """
            UPDATE $tableName
            SET success_count = ?,
                failure_count = ?,
                last_success_at = ?,
                last_failure_at = ?,
                last_updated_at = ?
            WHERE scope_key = ? AND route_key = ?
            """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.setInt(1, summary.successCount)
            statement.setInt(2, summary.failureCount)
            statement.setString(3, summary.lastSuccessAt)
            statement.setString(4, summary.lastFailureAt)
            statement.setString(5, summary.lastUpdatedAt)
            statement.setString(6, summary.scopeKey)
            statement.setString(7, summary.routeKey)
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

internal object RoutingTelemetryStoreFactory {
    fun create(persistenceConfiguration: PersistenceRuntimeConfiguration): RoutingTelemetryStore {
        val normalizedBackend = persistenceConfiguration.backend.lowercase()
        return when (normalizedBackend) {
            "jdbc", "postgres", "postgresql" -> {
                val jdbcUrl =
                    requireNotNull(persistenceConfiguration.jdbcUrl) {
                        "BERTBOT_STATE_JDBC_URL must be set when BERTBOT_STATE_STORE is '$normalizedBackend'."
                    }
                JdbcRoutingTelemetryStore(
                    jdbcUrl = jdbcUrl,
                    username = persistenceConfiguration.jdbcUser,
                    password = persistenceConfiguration.jdbcPassword,
                    tableName = persistenceConfiguration.routingTelemetryJdbcTable,
                )
            }
            else -> FileRoutingTelemetryStore(file = File(persistenceConfiguration.routingTelemetryFilePath))
        }
    }
}

internal fun computeRoutingHint(
    summary: RoutingTelemetrySummary,
    configuration: RoutingHintRuntimeConfiguration,
    now: Instant = Instant.now(),
): RoutingHint? {
    if (!configuration.enabled) return null

    val sampleSize = summary.successCount + summary.failureCount
    if (sampleSize < configuration.minSamplesPerRoute) {
        return null
    }

    val recencyWeight =
        recencyWeight(
            latestIsoTimestamp = summary.lastUpdatedAt,
            halfLifeHours = configuration.recencyHalfLifeHours,
            now = now,
        )
    val successRate = summary.successCount.toDouble() / sampleSize.toDouble()
    val centered = (successRate - 0.5) * 2.0
    val boundedInfluence = configuration.maxInfluence.coerceIn(0.0, 1.0)
    val confidence = (sampleSize.toDouble() / (sampleSize.toDouble() + 8.0)).coerceIn(0.0, 1.0)
    val weighted = centered * confidence * recencyWeight * boundedInfluence

    val successBias = weighted.coerceAtLeast(0.0)
    val failureDampening = (-weighted).coerceAtLeast(0.0)
    val score = (1.0 + weighted).coerceIn(1.0 - boundedInfluence, 1.0 + boundedInfluence)

    return RoutingHint(
        routeKey = summary.routeKey,
        score = score,
        confidence = confidence,
        successBias = successBias,
        failureDampening = failureDampening,
        sampleSize = sampleSize,
        reason =
            "route=${summary.routeKey} sampleSize=$sampleSize successRate=${"%.2f".format(successRate)} " +
                "confidence=${"%.2f".format(confidence)} recency=${"%.2f".format(recencyWeight)} influence=${"%.2f".format(boundedInfluence)}",
    )
}

private fun recencyWeight(
    latestIsoTimestamp: String?,
    halfLifeHours: Int,
    now: Instant,
): Double {
    val parsed = runCatching { latestIsoTimestamp?.let { Instant.parse(it) } }.getOrNull() ?: return 1.0
    val elapsedSeconds = (now.epochSecond - parsed.epochSecond).coerceAtLeast(0)
    val halfLifeSeconds = halfLifeHours.coerceAtLeast(1).toDouble() * 3600.0
    return Math.pow(0.5, elapsedSeconds / halfLifeSeconds).coerceIn(0.0, 1.0)
}
