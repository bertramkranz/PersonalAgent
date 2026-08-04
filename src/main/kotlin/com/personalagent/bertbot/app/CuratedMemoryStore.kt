package com.personalagent.bertbot.app

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.personalagent.bertbot.graph.store.DriverManagerJdbcConnectionProvider
import com.personalagent.bertbot.memory.PersistenceScopeKey
import java.io.File
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

internal data class CuratedMemoryEntry(
    val id: String,
    val scopeKey: String,
    val category: String,
    val content: String,
    val source: String,
    val confidence: Double? = null,
    val pinned: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
)

internal data class CuratedMemoryCreateRequest(
    val category: String,
    val content: String,
    val source: String,
    val confidence: Double? = null,
    val pinned: Boolean = false,
)

internal data class CuratedMemoryUpdateRequest(
    val category: String? = null,
    val content: String? = null,
    val source: String? = null,
    val confidence: Double? = null,
    val pinned: Boolean? = null,
)

internal interface CuratedMemoryStore {
    fun add(request: CuratedMemoryCreateRequest): CuratedMemoryEntry

    fun list(limit: Int = 200): List<CuratedMemoryEntry>

    fun get(id: String): CuratedMemoryEntry?

    fun update(
        id: String,
        request: CuratedMemoryUpdateRequest,
    ): CuratedMemoryEntry?

    fun remove(id: String): Boolean

    fun clear()

    fun <T> withScope(
        scopeKey: String,
        action: () -> T,
    ): T = action()
}

internal class FileCuratedMemoryStore(
    private val file: File,
    private val gson: Gson = Gson(),
    private val maxEntriesPerScope: Int,
) : CuratedMemoryStore {
    private val lock = Any()
    private val currentScope = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }
    private val legacyScopeAlias = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }
    private var loadedScopeKey: String? = null
    private var cachedEntries: List<CuratedMemoryEntry> = emptyList()

    override fun add(request: CuratedMemoryCreateRequest): CuratedMemoryEntry {
        synchronized(lock) {
            ensureLoadedForCurrentScope()
            val normalized = request.normalized()
            val now = Instant.now().toString()
            val scope = currentScope.get()
            val all = cachedEntries.toMutableList()
            val created =
                CuratedMemoryEntry(
                    id = UUID.randomUUID().toString(),
                    scopeKey = scope,
                    category = normalized.category,
                    content = normalized.content,
                    source = normalized.source,
                    confidence = normalized.confidence,
                    pinned = normalized.pinned,
                    createdAt = now,
                    updatedAt = now,
                )
            all += created
            persistForCurrentScope(evictToLimit(all))
            return created
        }
    }

    override fun list(limit: Int): List<CuratedMemoryEntry> {
        val bounded = limit.coerceIn(1, 10_000)
        synchronized(lock) {
            ensureLoadedForCurrentScope()
            return cachedEntries.takeLast(bounded)
        }
    }

    override fun get(id: String): CuratedMemoryEntry? {
        val normalizedId = id.trim()
        if (normalizedId.isBlank()) {
            return null
        }
        synchronized(lock) {
            ensureLoadedForCurrentScope()
            return cachedEntries.firstOrNull { it.id == normalizedId }
        }
    }

    override fun update(
        id: String,
        request: CuratedMemoryUpdateRequest,
    ): CuratedMemoryEntry? {
        val normalizedId = id.trim()
        if (normalizedId.isBlank()) {
            return null
        }
        synchronized(lock) {
            ensureLoadedForCurrentScope()
            val all = cachedEntries.toMutableList()
            val index = all.indexOfFirst { it.id == normalizedId }
            if (index < 0) {
                return null
            }
            val now = Instant.now().toString()
            val existing = all[index]
            val updated =
                existing.copy(
                    category = request.category?.normalizeCategory() ?: existing.category,
                    content = request.content?.normalizeContent() ?: existing.content,
                    source = request.source?.normalizeSource() ?: existing.source,
                    confidence = request.confidence?.coerceIn(0.0, 1.0) ?: existing.confidence,
                    pinned = request.pinned ?: existing.pinned,
                    updatedAt = now,
                )
            all[index] = updated
            persistForCurrentScope(evictToLimit(all))
            return updated
        }
    }

    override fun remove(id: String): Boolean {
        val normalizedId = id.trim()
        if (normalizedId.isBlank()) {
            return false
        }
        synchronized(lock) {
            ensureLoadedForCurrentScope()
            val all = cachedEntries.toMutableList()
            val removed = all.removeIf { it.id == normalizedId }
            if (removed) {
                persistForCurrentScope(all)
            }
            return removed
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

    private fun loadAllForCurrentScope(): List<CuratedMemoryEntry> {
        val scopedFile = scopedFile()
        val legacyFile = legacyScopedFile()
        val existingFile = if (scopedFile.exists()) scopedFile else legacyFile
        if (!existingFile.exists()) {
            return emptyList()
        }
        if (existingFile == legacyFile && legacyFile != scopedFile) {
            println("Warning: curated memory store loaded legacy scoped file '${legacyFile.path}' because normalized scoped file '${scopedFile.path}' was not found.")
        }

        val content = existingFile.readText().trim()
        if (content.isBlank()) {
            return emptyList()
        }

        return runCatching {
            gson.fromJson(content, Array<CuratedMemoryEntry>::class.java)
                ?.toList()
                ?.map { it.normalized() }
                ?: emptyList()
        }.getOrElse { error ->
            if (error is JsonSyntaxException) {
                println("Warning: failed to parse curated memory file '${existingFile.path}'. Returning empty list.")
                emptyList()
            } else {
                throw error
            }
        }
    }

    private fun ensureLoadedForCurrentScope() {
        val scope = currentScope.get()
        if (loadedScopeKey == scope) {
            return
        }
        cachedEntries = loadAllForCurrentScope()
        loadedScopeKey = scope
    }

    private fun persistForCurrentScope(entries: List<CuratedMemoryEntry>) {
        val scopedFile = scopedFile()
        scopedFile.parentFile?.mkdirs()
        val normalizedEntries = entries.map { it.normalized() }
        scopedFile.writeText(gson.toJson(normalizedEntries))
        cachedEntries = normalizedEntries
        loadedScopeKey = currentScope.get()
    }

    private fun scopedFile(): File {
        val scope = currentScope.get()
        if (scope == PersistenceScopeKey.defaultScopeKey()) {
            return file
        }

        val parent = file.parentFile ?: File(".")
        val stem = file.nameWithoutExtension
        val ext = file.extension.takeIf { it.isNotBlank() } ?: "json"
        return File(parent, "$stem-$scope.$ext")
    }

    private fun legacyScopedFile(): File {
        val scope = legacyScopeAlias.get()
        if (scope == PersistenceScopeKey.defaultScopeKey()) {
            return file
        }

        val parent = file.parentFile ?: File(".")
        val stem = file.nameWithoutExtension
        val ext = file.extension.takeIf { it.isNotBlank() } ?: "json"
        return File(parent, "$stem-$scope.$ext")
    }

    private fun evictToLimit(entries: List<CuratedMemoryEntry>): List<CuratedMemoryEntry> {
        if (entries.size <= maxEntriesPerScope) {
            return entries
        }

        val mutable = entries.toMutableList()
        while (mutable.size > maxEntriesPerScope) {
            val oldestUnpinnedIndex = mutable.indexOfFirst { !it.pinned }
            if (oldestUnpinnedIndex >= 0) {
                mutable.removeAt(oldestUnpinnedIndex)
            } else {
                mutable.removeAt(0)
            }
        }
        return mutable
    }
}

internal class JdbcCuratedMemoryStore(
    jdbcUrl: String,
    username: String? = null,
    password: String? = null,
    private val tableName: String,
    private val gson: Gson = Gson(),
    private val maxEntriesPerScope: Int,
) : CuratedMemoryStore {
    private val connectionProvider = DriverManagerJdbcConnectionProvider(jdbcUrl, username, password)
    private val lock = Any()
    private val currentScope = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }
    private val legacyScopeAlias = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }

    init {
        require(tableName.matches(TABLE_NAME_REGEX)) {
            "tableName must match ${TABLE_NAME_REGEX.pattern}"
        }
        initializeSchema()
    }

    override fun add(request: CuratedMemoryCreateRequest): CuratedMemoryEntry {
        synchronized(lock) {
            val normalized = request.normalized()
            val now = Instant.now().toString()
            val scope = currentScope.get()
            val created =
                CuratedMemoryEntry(
                    id = UUID.randomUUID().toString(),
                    scopeKey = scope,
                    category = normalized.category,
                    content = normalized.content,
                    source = normalized.source,
                    confidence = normalized.confidence,
                    pinned = normalized.pinned,
                    createdAt = now,
                    updatedAt = now,
                )
            withConnection { connection ->
                connection.autoCommit = false
                try {
                    insertEntry(connection, created)
                    trimExcessRows(connection, scope)
                    connection.commit()
                } catch (e: Exception) {
                    runCatching { connection.rollback() }
                    throw e
                } finally {
                    connection.autoCommit = true
                }
            }
            return created
        }
    }

    override fun list(limit: Int): List<CuratedMemoryEntry> {
        val bounded = limit.coerceIn(1, 10_000)
        synchronized(lock) {
            return withConnection { connection ->
                val scope = currentScope.get()
                val legacyScope = legacyScopeAlias.get()
                val primary = queryByScope(connection, scope, bounded)
                if (primary.isNotEmpty()) {
                    primary
                } else {
                    val legacy = queryByScope(connection, legacyScope, bounded)
                    if (legacy.isNotEmpty() && legacyScope != scope) {
                        println("Warning: curated memory store loaded legacy scoped rows for scope_key='$legacyScope' because normalized scope_key='$scope' was not found.")
                    }
                    legacy
                }
            }
        }
    }

    override fun get(id: String): CuratedMemoryEntry? {
        val normalizedId = id.trim()
        if (normalizedId.isBlank()) {
            return null
        }
        synchronized(lock) {
            return withConnection { connection ->
                val sql = "SELECT payload FROM $tableName WHERE scope_key = ? AND entry_id = ? LIMIT 1"
                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, currentScope.get())
                    statement.setString(2, normalizedId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) {
                            null
                        } else {
                            gson.fromJson(rs.getString("payload"), CuratedMemoryEntry::class.java)?.normalized()
                        }
                    }
                }
            }
        }
    }

    override fun update(
        id: String,
        request: CuratedMemoryUpdateRequest,
    ): CuratedMemoryEntry? {
        val normalizedId = id.trim()
        if (normalizedId.isBlank()) {
            return null
        }
        synchronized(lock) {
            return withConnection { connection ->
                connection.autoCommit = false
                try {
                    val existing = findById(connection, normalizedId) ?: return@withConnection null
                    val updated =
                        existing.copy(
                            category = request.category?.normalizeCategory() ?: existing.category,
                            content = request.content?.normalizeContent() ?: existing.content,
                            source = request.source?.normalizeSource() ?: existing.source,
                            confidence = request.confidence?.coerceIn(0.0, 1.0) ?: existing.confidence,
                            pinned = request.pinned ?: existing.pinned,
                            updatedAt = Instant.now().toString(),
                        )
                    val sql = "UPDATE $tableName SET payload = ?, updated_at = CURRENT_TIMESTAMP WHERE scope_key = ? AND entry_id = ?"
                    connection.prepareStatement(sql).use { statement ->
                        statement.setString(1, gson.toJson(updated))
                        statement.setString(2, currentScope.get())
                        statement.setString(3, normalizedId)
                        statement.executeUpdate()
                    }
                    connection.commit()
                    updated
                } catch (e: Exception) {
                    runCatching { connection.rollback() }
                    throw e
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }

    override fun remove(id: String): Boolean {
        val normalizedId = id.trim()
        if (normalizedId.isBlank()) {
            return false
        }
        synchronized(lock) {
            return withConnection { connection ->
                val sql = "DELETE FROM $tableName WHERE scope_key = ? AND entry_id = ?"
                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, currentScope.get())
                    statement.setString(2, normalizedId)
                    statement.executeUpdate() > 0
                }
            }
        }
    }

    override fun clear() {
        synchronized(lock) {
            withConnection { connection ->
                val sql = "DELETE FROM $tableName WHERE scope_key = ?"
                connection.prepareStatement(sql).use { statement ->
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
                        entry_id TEXT NOT NULL,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        payload TEXT NOT NULL,
                        UNIQUE (scope_key, entry_id)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    "CREATE INDEX IF NOT EXISTS ${tableName}_scope_id_idx ON $tableName (scope_key, id)",
                )
            }
        }
    }

    private fun insertEntry(
        connection: Connection,
        entry: CuratedMemoryEntry,
    ) {
        val sql = "INSERT INTO $tableName (scope_key, entry_id, payload) VALUES (?, ?, ?)"
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, currentScope.get())
            statement.setString(2, entry.id)
            statement.setString(3, gson.toJson(entry))
            statement.executeUpdate()
        }
    }

    private fun trimExcessRows(
        connection: Connection,
        scopeKey: String,
    ) {
        val allIdsInOrder = queryScopeRows(connection, scopeKey)
        if (allIdsInOrder.size <= maxEntriesPerScope) {
            return
        }

        val survivors = allIdsInOrder.toMutableList()
        while (survivors.size > maxEntriesPerScope) {
            val oldestUnpinnedIndex = survivors.indexOfFirst { row -> !row.entry.pinned }
            if (oldestUnpinnedIndex >= 0) {
                survivors.removeAt(oldestUnpinnedIndex)
            } else {
                survivors.removeAt(0)
            }
        }

        val survivorIds = survivors.map { it.id }.toSet()
        val deleteIds = allIdsInOrder.map { it.id }.filterNot { id -> id in survivorIds }
        if (deleteIds.isEmpty()) {
            return
        }

        val placeholders = deleteIds.joinToString(separator = ",") { "?" }
        val deleteSql = "DELETE FROM $tableName WHERE scope_key = ? AND id IN ($placeholders)"
        connection.prepareStatement(deleteSql).use { statement ->
            statement.setString(1, scopeKey)
            deleteIds.forEachIndexed { index, id ->
                statement.setLong(index + 2, id)
            }
            statement.executeUpdate()
        }
    }

    private fun queryByScope(
        connection: Connection,
        scopeKey: String,
        limit: Int,
    ): List<CuratedMemoryEntry> {
        val sql =
            """
            SELECT payload
            FROM $tableName
            WHERE scope_key = ?
            ORDER BY id ASC
            LIMIT ?
            """.trimIndent()
        return connection.prepareStatement(sql).use { statement ->
            statement.setString(1, scopeKey)
            statement.setInt(2, limit)
            statement.executeQuery().use { rs ->
                parseEntryRows(rs)
            }
        }
    }

    private fun queryScopeRows(
        connection: Connection,
        scopeKey: String,
    ): List<CuratedMemoryScopeRow> {
        val sql =
            """
            SELECT id, payload
            FROM $tableName
            WHERE scope_key = ?
            ORDER BY id ASC
            """.trimIndent()
        return connection.prepareStatement(sql).use { statement ->
            statement.setString(1, scopeKey)
            statement.executeQuery().use { rs ->
                parseScopeRows(rs)
            }
        }
    }

    private fun parseEntryRows(rs: ResultSet): List<CuratedMemoryEntry> {
        val rows = mutableListOf<CuratedMemoryEntry>()
        while (rs.next()) {
            val parsed =
                runCatching {
                    gson.fromJson(rs.getString("payload"), CuratedMemoryEntry::class.java)?.normalized()
                }.getOrNull()
            if (parsed != null) {
                rows += parsed
            }
        }
        return rows
    }

    private fun parseScopeRows(rs: ResultSet): List<CuratedMemoryScopeRow> {
        val rows = mutableListOf<CuratedMemoryScopeRow>()
        while (rs.next()) {
            val parsed =
                runCatching {
                    gson.fromJson(rs.getString("payload"), CuratedMemoryEntry::class.java)?.normalized()
                }.getOrNull()
            if (parsed != null) {
                rows += CuratedMemoryScopeRow(id = rs.getLong("id"), entry = parsed)
            }
        }
        return rows
    }

    private fun findById(
        connection: Connection,
        id: String,
    ): CuratedMemoryEntry? {
        val sql = "SELECT payload FROM $tableName WHERE scope_key = ? AND entry_id = ? LIMIT 1"
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, currentScope.get())
            statement.setString(2, id)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    return null
                }
                return runCatching {
                    gson.fromJson(rs.getString("payload"), CuratedMemoryEntry::class.java)?.normalized()
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

private data class CuratedMemoryScopeRow(
    val id: Long,
    val entry: CuratedMemoryEntry,
)

internal object CuratedMemoryStoreFactory {
    fun create(
        persistenceConfiguration: PersistenceRuntimeConfiguration,
        maxEntriesPerScope: Int,
    ): CuratedMemoryStore {
        val normalizedBackend = persistenceConfiguration.backend.lowercase()
        return when (normalizedBackend) {
            "jdbc", "postgres", "postgresql" -> {
                val jdbcUrl =
                    requireNotNull(persistenceConfiguration.jdbcUrl) {
                        "BERTBOT_STATE_JDBC_URL must be set when BERTBOT_STATE_STORE is '$normalizedBackend'."
                    }
                JdbcCuratedMemoryStore(
                    jdbcUrl = jdbcUrl,
                    username = persistenceConfiguration.jdbcUser,
                    password = persistenceConfiguration.jdbcPassword,
                    tableName = persistenceConfiguration.curatedMemoryJdbcTable,
                    maxEntriesPerScope = maxEntriesPerScope,
                )
            }
            else ->
                FileCuratedMemoryStore(
                    file = File(persistenceConfiguration.curatedMemoryFilePath),
                    maxEntriesPerScope = maxEntriesPerScope,
                )
        }
    }
}

private fun CuratedMemoryCreateRequest.normalized(): CuratedMemoryCreateRequest =
    copy(
        category = category.normalizeCategory(),
        content = content.normalizeContent(),
        source = source.normalizeSource(),
        confidence = confidence?.coerceIn(0.0, 1.0),
    )

private fun CuratedMemoryEntry.normalized(): CuratedMemoryEntry =
    copy(
        category = category.normalizeCategory(),
        content = content.normalizeContent(),
        source = source.normalizeSource(),
        confidence = confidence?.coerceIn(0.0, 1.0),
    )

private fun String.normalizeCategory(): String = trim().ifBlank { "general" }.take(64)

private fun String.normalizeContent(): String = trim().ifBlank { "(empty)" }.take(4_000)

private fun String.normalizeSource(): String = trim().ifBlank { "unknown" }.take(128)
