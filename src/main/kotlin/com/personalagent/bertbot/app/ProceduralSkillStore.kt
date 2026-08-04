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

internal enum class ProceduralSkillStatus {
    PENDING_APPROVAL,
    ACTIVE,
    ARCHIVED,
    SUPERSEDED,
    REJECTED,
}

internal enum class ProceduralSkillOperation {
    CREATE,
    PATCH,
    SUPERSEDE,
    ARCHIVE,
}

internal data class ProceduralSkillArtifact(
    val skillId: String,
    val scopeKey: String,
    val slug: String,
    val title: String,
    val instructions: String,
    val version: Int,
    val status: ProceduralSkillStatus,
    val pendingOperation: ProceduralSkillOperation? = null,
    val supersedesSkillId: String? = null,
    val supersededBySkillId: String? = null,
    val decisionNote: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val approvedAt: String? = null,
    val rejectedAt: String? = null,
)

internal data class ProceduralSkillCreateRequest(
    val slug: String,
    val title: String,
    val instructions: String,
    val staged: Boolean = true,
)

internal data class ProceduralSkillPatchRequest(
    val title: String? = null,
    val instructions: String? = null,
    val staged: Boolean = true,
)

internal data class ProceduralSkillSupersedeRequest(
    val slug: String,
    val title: String,
    val instructions: String,
    val staged: Boolean = true,
)

internal interface ProceduralSkillStore {
    fun create(request: ProceduralSkillCreateRequest): ProceduralSkillArtifact

    fun patch(
        skillId: String,
        request: ProceduralSkillPatchRequest,
    ): ProceduralSkillArtifact?

    fun supersede(
        skillId: String,
        request: ProceduralSkillSupersedeRequest,
    ): ProceduralSkillArtifact?

    fun archive(
        skillId: String,
        staged: Boolean = true,
    ): ProceduralSkillArtifact?

    fun approve(
        skillId: String,
        note: String? = null,
    ): ProceduralSkillArtifact?

    fun reject(
        skillId: String,
        note: String? = null,
    ): ProceduralSkillArtifact?

    fun list(
        status: ProceduralSkillStatus? = null,
        limit: Int = 200,
    ): List<ProceduralSkillArtifact>

    fun get(skillId: String): ProceduralSkillArtifact?

    fun clear()

    fun <T> withScope(
        scopeKey: String,
        action: () -> T,
    ): T = action()
}

internal class FileProceduralSkillStore(
    private val file: File,
    private val gson: Gson = Gson(),
) : ProceduralSkillStore {
    private val lock = Any()
    private val currentScope = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }
    private val legacyScopeAlias = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }

    override fun create(request: ProceduralSkillCreateRequest): ProceduralSkillArtifact {
        synchronized(lock) {
            val normalized = request.normalized()
            val now = Instant.now().toString()
            val created =
                ProceduralSkillArtifact(
                    skillId = UUID.randomUUID().toString(),
                    scopeKey = currentScope.get(),
                    slug = normalized.slug,
                    title = normalized.title,
                    instructions = normalized.instructions,
                    version = 1,
                    status = if (normalized.staged) ProceduralSkillStatus.PENDING_APPROVAL else ProceduralSkillStatus.ACTIVE,
                    pendingOperation = if (normalized.staged) ProceduralSkillOperation.CREATE else null,
                    createdAt = now,
                    updatedAt = now,
                    approvedAt = if (normalized.staged) null else now,
                )
            val all = loadAllForCurrentScope().toMutableList()
            all += created
            persistForCurrentScope(all)
            return created
        }
    }

    override fun patch(
        skillId: String,
        request: ProceduralSkillPatchRequest,
    ): ProceduralSkillArtifact? {
        val normalizedSkillId = skillId.trim()
        if (normalizedSkillId.isBlank()) return null

        synchronized(lock) {
            val all = loadAllForCurrentScope().toMutableList()
            val currentIndex = all.indexOfFirst { it.skillId == normalizedSkillId }
            if (currentIndex < 0) return null

            val current = all[currentIndex]
            if (current.status != ProceduralSkillStatus.ACTIVE) return null

            val normalizedRequest = request.normalized()
            val now = Instant.now().toString()

            if (normalizedRequest.staged) {
                val candidate =
                    current.copy(
                        skillId = UUID.randomUUID().toString(),
                        title = normalizedRequest.title ?: current.title,
                        instructions = normalizedRequest.instructions ?: current.instructions,
                        version = current.version + 1,
                        status = ProceduralSkillStatus.PENDING_APPROVAL,
                        pendingOperation = ProceduralSkillOperation.PATCH,
                        supersedesSkillId = current.skillId,
                        supersededBySkillId = null,
                        decisionNote = null,
                        updatedAt = now,
                        approvedAt = null,
                        rejectedAt = null,
                    )
                all += candidate
                persistForCurrentScope(all)
                return candidate
            }

            val updated =
                current.copy(
                    title = normalizedRequest.title ?: current.title,
                    instructions = normalizedRequest.instructions ?: current.instructions,
                    version = current.version + 1,
                    updatedAt = now,
                )
            all[currentIndex] = updated
            persistForCurrentScope(all)
            return updated
        }
    }

    override fun supersede(
        skillId: String,
        request: ProceduralSkillSupersedeRequest,
    ): ProceduralSkillArtifact? {
        val normalizedSkillId = skillId.trim()
        if (normalizedSkillId.isBlank()) return null

        synchronized(lock) {
            val all = loadAllForCurrentScope().toMutableList()
            val currentIndex = all.indexOfFirst { it.skillId == normalizedSkillId }
            if (currentIndex < 0) return null

            val current = all[currentIndex]
            if (current.status != ProceduralSkillStatus.ACTIVE) return null

            val normalizedRequest = request.normalized()
            val now = Instant.now().toString()
            val replacement =
                ProceduralSkillArtifact(
                    skillId = UUID.randomUUID().toString(),
                    scopeKey = currentScope.get(),
                    slug = normalizedRequest.slug,
                    title = normalizedRequest.title,
                    instructions = normalizedRequest.instructions,
                    version = current.version + 1,
                    status = if (normalizedRequest.staged) ProceduralSkillStatus.PENDING_APPROVAL else ProceduralSkillStatus.ACTIVE,
                    pendingOperation = if (normalizedRequest.staged) ProceduralSkillOperation.SUPERSEDE else null,
                    supersedesSkillId = current.skillId,
                    createdAt = now,
                    updatedAt = now,
                    approvedAt = if (normalizedRequest.staged) null else now,
                )

            if (normalizedRequest.staged) {
                all += replacement
            } else {
                all[currentIndex] =
                    current.copy(
                        status = ProceduralSkillStatus.SUPERSEDED,
                        supersededBySkillId = replacement.skillId,
                        updatedAt = now,
                    )
                all += replacement
            }

            persistForCurrentScope(all)
            return replacement
        }
    }

    override fun archive(
        skillId: String,
        staged: Boolean,
    ): ProceduralSkillArtifact? {
        val normalizedSkillId = skillId.trim()
        if (normalizedSkillId.isBlank()) return null

        synchronized(lock) {
            val all = loadAllForCurrentScope().toMutableList()
            val currentIndex = all.indexOfFirst { it.skillId == normalizedSkillId }
            if (currentIndex < 0) return null

            val current = all[currentIndex]
            if (current.status != ProceduralSkillStatus.ACTIVE && current.status != ProceduralSkillStatus.PENDING_APPROVAL) {
                return null
            }

            val now = Instant.now().toString()
            val updated =
                if (staged) {
                    current.copy(
                        status = ProceduralSkillStatus.PENDING_APPROVAL,
                        pendingOperation = ProceduralSkillOperation.ARCHIVE,
                        updatedAt = now,
                    )
                } else {
                    current.copy(
                        status = ProceduralSkillStatus.ARCHIVED,
                        pendingOperation = null,
                        approvedAt = now,
                        updatedAt = now,
                    )
                }

            all[currentIndex] = updated
            persistForCurrentScope(all)
            return updated
        }
    }

    override fun approve(
        skillId: String,
        note: String?,
    ): ProceduralSkillArtifact? {
        val normalizedSkillId = skillId.trim()
        if (normalizedSkillId.isBlank()) return null

        synchronized(lock) {
            val all = loadAllForCurrentScope().toMutableList()
            val targetIndex = all.indexOfFirst { it.skillId == normalizedSkillId }
            if (targetIndex < 0) return null

            val target = all[targetIndex]
            if (target.status != ProceduralSkillStatus.PENDING_APPROVAL) return null

            val now = Instant.now().toString()
            val decisionNote = note?.trim()?.ifBlank { null }

            return when (target.pendingOperation) {
                ProceduralSkillOperation.ARCHIVE -> {
                    val archived =
                        target.copy(
                            status = ProceduralSkillStatus.ARCHIVED,
                            pendingOperation = null,
                            decisionNote = decisionNote,
                            approvedAt = now,
                            updatedAt = now,
                        )
                    all[targetIndex] = archived
                    persistForCurrentScope(all)
                    archived
                }
                ProceduralSkillOperation.CREATE,
                ProceduralSkillOperation.PATCH,
                ProceduralSkillOperation.SUPERSEDE,
                null,
                -> {
                    val activated =
                        target.copy(
                            status = ProceduralSkillStatus.ACTIVE,
                            pendingOperation = null,
                            decisionNote = decisionNote,
                            approvedAt = now,
                            rejectedAt = null,
                            updatedAt = now,
                        )
                    all[targetIndex] = activated
                    applySupersedeSideEffect(all, activated, now)
                    persistForCurrentScope(all)
                    activated
                }
            }
        }
    }

    override fun reject(
        skillId: String,
        note: String?,
    ): ProceduralSkillArtifact? {
        val normalizedSkillId = skillId.trim()
        if (normalizedSkillId.isBlank()) return null

        synchronized(lock) {
            val all = loadAllForCurrentScope().toMutableList()
            val targetIndex = all.indexOfFirst { it.skillId == normalizedSkillId }
            if (targetIndex < 0) return null

            val target = all[targetIndex]
            if (target.status != ProceduralSkillStatus.PENDING_APPROVAL) return null

            val now = Instant.now().toString()
            val decisionNote = note?.trim()?.ifBlank { null }

            val rejected =
                if (target.pendingOperation == ProceduralSkillOperation.ARCHIVE) {
                    target.copy(
                        status = ProceduralSkillStatus.ACTIVE,
                        pendingOperation = null,
                        decisionNote = decisionNote,
                        rejectedAt = now,
                        updatedAt = now,
                    )
                } else {
                    target.copy(
                        status = ProceduralSkillStatus.REJECTED,
                        pendingOperation = null,
                        decisionNote = decisionNote,
                        rejectedAt = now,
                        updatedAt = now,
                    )
                }
            all[targetIndex] = rejected
            persistForCurrentScope(all)
            return rejected
        }
    }

    override fun list(
        status: ProceduralSkillStatus?,
        limit: Int,
    ): List<ProceduralSkillArtifact> {
        val bounded = limit.coerceIn(1, 10_000)
        synchronized(lock) {
            val all = loadAllForCurrentScope()
            val filtered = if (status == null) all else all.filter { it.status == status }
            return filtered.takeLast(bounded)
        }
    }

    override fun get(skillId: String): ProceduralSkillArtifact? {
        val normalizedSkillId = skillId.trim()
        if (normalizedSkillId.isBlank()) return null

        synchronized(lock) {
            return loadAllForCurrentScope().firstOrNull { it.skillId == normalizedSkillId }
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

    private fun applySupersedeSideEffect(
        entries: MutableList<ProceduralSkillArtifact>,
        activated: ProceduralSkillArtifact,
        now: String,
    ) {
        val supersededId = activated.supersedesSkillId ?: return
        val oldIndex = entries.indexOfFirst { it.skillId == supersededId }
        if (oldIndex < 0) return

        val old = entries[oldIndex]
        if (old.status == ProceduralSkillStatus.ACTIVE) {
            entries[oldIndex] =
                old.copy(
                    status = ProceduralSkillStatus.SUPERSEDED,
                    supersededBySkillId = activated.skillId,
                    updatedAt = now,
                )
        }
    }

    private fun loadAllForCurrentScope(): List<ProceduralSkillArtifact> {
        val scopedFile = scopedFile()
        val legacyFile = legacyScopedFile()
        val existingFile = if (scopedFile.exists()) scopedFile else legacyFile
        if (!existingFile.exists()) return emptyList()

        if (existingFile == legacyFile && legacyFile != scopedFile) {
            println("Warning: procedural skill store loaded legacy scoped file '${legacyFile.path}' because normalized scoped file '${scopedFile.path}' was not found.")
        }

        val content = existingFile.readText().trim()
        if (content.isBlank()) return emptyList()

        return runCatching {
            gson.fromJson(content, Array<ProceduralSkillArtifact>::class.java)
                ?.toList()
                ?.map { it.normalized() }
                ?: emptyList()
        }.getOrElse { error ->
            if (error is JsonSyntaxException) {
                println("Warning: failed to parse procedural skill file '${existingFile.path}'. Returning empty list.")
                emptyList()
            } else {
                throw error
            }
        }
    }

    private fun persistForCurrentScope(entries: List<ProceduralSkillArtifact>) {
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

    private fun legacyScopedFile(): File {
        val scope = legacyScopeAlias.get()
        if (scope == PersistenceScopeKey.defaultScopeKey()) return file

        val parent = file.parentFile ?: File(".")
        val stem = file.nameWithoutExtension
        val ext = file.extension.takeIf { it.isNotBlank() } ?: "json"
        return File(parent, "$stem-$scope.$ext")
    }
}

internal class JdbcProceduralSkillStore(
    jdbcUrl: String,
    username: String? = null,
    password: String? = null,
    private val tableName: String,
    private val gson: Gson = Gson(),
) : ProceduralSkillStore {
    private val connectionProvider = DriverManagerJdbcConnectionProvider(jdbcUrl, username, password)
    private val lock = Any()
    private val currentScope = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }
    private val legacyScopeAlias = ThreadLocal.withInitial { PersistenceScopeKey.defaultScopeKey() }

    init {
        require(tableName.matches(TABLE_NAME_REGEX)) { "tableName must match ${TABLE_NAME_REGEX.pattern}" }
        initializeSchema()
    }

    override fun create(request: ProceduralSkillCreateRequest): ProceduralSkillArtifact {
        synchronized(lock) {
            val normalized = request.normalized()
            val now = Instant.now().toString()
            val created =
                ProceduralSkillArtifact(
                    skillId = UUID.randomUUID().toString(),
                    scopeKey = currentScope.get(),
                    slug = normalized.slug,
                    title = normalized.title,
                    instructions = normalized.instructions,
                    version = 1,
                    status = if (normalized.staged) ProceduralSkillStatus.PENDING_APPROVAL else ProceduralSkillStatus.ACTIVE,
                    pendingOperation = if (normalized.staged) ProceduralSkillOperation.CREATE else null,
                    createdAt = now,
                    updatedAt = now,
                    approvedAt = if (normalized.staged) null else now,
                )
            withConnection { connection -> insertEntry(connection, created) }
            return created
        }
    }

    override fun patch(
        skillId: String,
        request: ProceduralSkillPatchRequest,
    ): ProceduralSkillArtifact? {
        val normalizedSkillId = skillId.trim()
        if (normalizedSkillId.isBlank()) return null

        synchronized(lock) {
            return withConnection { connection ->
                connection.autoCommit = false
                try {
                    val current = findById(connection, normalizedSkillId) ?: return@withConnection null
                    if (current.status != ProceduralSkillStatus.ACTIVE) return@withConnection null

                    val normalizedRequest = request.normalized()
                    val now = Instant.now().toString()
                    val result =
                        if (normalizedRequest.staged) {
                            val candidate =
                                current.copy(
                                    skillId = UUID.randomUUID().toString(),
                                    title = normalizedRequest.title ?: current.title,
                                    instructions = normalizedRequest.instructions ?: current.instructions,
                                    version = current.version + 1,
                                    status = ProceduralSkillStatus.PENDING_APPROVAL,
                                    pendingOperation = ProceduralSkillOperation.PATCH,
                                    supersedesSkillId = current.skillId,
                                    supersededBySkillId = null,
                                    decisionNote = null,
                                    updatedAt = now,
                                    approvedAt = null,
                                    rejectedAt = null,
                                )
                            insertEntry(connection, candidate)
                            candidate
                        } else {
                            val updated =
                                current.copy(
                                    title = normalizedRequest.title ?: current.title,
                                    instructions = normalizedRequest.instructions ?: current.instructions,
                                    version = current.version + 1,
                                    updatedAt = now,
                                )
                            updateEntry(connection, updated)
                            updated
                        }

                    connection.commit()
                    result
                } catch (error: Exception) {
                    runCatching { connection.rollback() }
                    throw error
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }

    override fun supersede(
        skillId: String,
        request: ProceduralSkillSupersedeRequest,
    ): ProceduralSkillArtifact? {
        val normalizedSkillId = skillId.trim()
        if (normalizedSkillId.isBlank()) return null

        synchronized(lock) {
            return withConnection { connection ->
                connection.autoCommit = false
                try {
                    val current = findById(connection, normalizedSkillId) ?: return@withConnection null
                    if (current.status != ProceduralSkillStatus.ACTIVE) return@withConnection null

                    val normalizedRequest = request.normalized()
                    val now = Instant.now().toString()
                    val replacement =
                        ProceduralSkillArtifact(
                            skillId = UUID.randomUUID().toString(),
                            scopeKey = currentScope.get(),
                            slug = normalizedRequest.slug,
                            title = normalizedRequest.title,
                            instructions = normalizedRequest.instructions,
                            version = current.version + 1,
                            status = if (normalizedRequest.staged) ProceduralSkillStatus.PENDING_APPROVAL else ProceduralSkillStatus.ACTIVE,
                            pendingOperation = if (normalizedRequest.staged) ProceduralSkillOperation.SUPERSEDE else null,
                            supersedesSkillId = current.skillId,
                            createdAt = now,
                            updatedAt = now,
                            approvedAt = if (normalizedRequest.staged) null else now,
                        )
                    insertEntry(connection, replacement)

                    if (!normalizedRequest.staged) {
                        updateEntry(
                            connection,
                            current.copy(
                                status = ProceduralSkillStatus.SUPERSEDED,
                                supersededBySkillId = replacement.skillId,
                                updatedAt = now,
                            ),
                        )
                    }

                    connection.commit()
                    replacement
                } catch (error: Exception) {
                    runCatching { connection.rollback() }
                    throw error
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }

    override fun archive(
        skillId: String,
        staged: Boolean,
    ): ProceduralSkillArtifact? {
        val normalizedSkillId = skillId.trim()
        if (normalizedSkillId.isBlank()) return null

        synchronized(lock) {
            return withConnection { connection ->
                val current = findById(connection, normalizedSkillId) ?: return@withConnection null
                if (current.status != ProceduralSkillStatus.ACTIVE && current.status != ProceduralSkillStatus.PENDING_APPROVAL) {
                    return@withConnection null
                }

                val now = Instant.now().toString()
                val updated =
                    if (staged) {
                        current.copy(
                            status = ProceduralSkillStatus.PENDING_APPROVAL,
                            pendingOperation = ProceduralSkillOperation.ARCHIVE,
                            updatedAt = now,
                        )
                    } else {
                        current.copy(
                            status = ProceduralSkillStatus.ARCHIVED,
                            pendingOperation = null,
                            approvedAt = now,
                            updatedAt = now,
                        )
                    }

                updateEntry(connection, updated)
                updated
            }
        }
    }

    override fun approve(
        skillId: String,
        note: String?,
    ): ProceduralSkillArtifact? {
        val normalizedSkillId = skillId.trim()
        if (normalizedSkillId.isBlank()) return null

        synchronized(lock) {
            return withConnection { connection ->
                connection.autoCommit = false
                try {
                    val target = findById(connection, normalizedSkillId) ?: return@withConnection null
                    if (target.status != ProceduralSkillStatus.PENDING_APPROVAL) return@withConnection null

                    val now = Instant.now().toString()
                    val decisionNote = note?.trim()?.ifBlank { null }
                    val approved =
                        when (target.pendingOperation) {
                            ProceduralSkillOperation.ARCHIVE ->
                                target.copy(
                                    status = ProceduralSkillStatus.ARCHIVED,
                                    pendingOperation = null,
                                    decisionNote = decisionNote,
                                    approvedAt = now,
                                    updatedAt = now,
                                )
                            ProceduralSkillOperation.CREATE,
                            ProceduralSkillOperation.PATCH,
                            ProceduralSkillOperation.SUPERSEDE,
                            null,
                            ->
                                target.copy(
                                    status = ProceduralSkillStatus.ACTIVE,
                                    pendingOperation = null,
                                    decisionNote = decisionNote,
                                    approvedAt = now,
                                    rejectedAt = null,
                                    updatedAt = now,
                                )
                        }
                    updateEntry(connection, approved)
                    applySupersedeSideEffect(connection, approved, now)
                    connection.commit()
                    approved
                } catch (error: Exception) {
                    runCatching { connection.rollback() }
                    throw error
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }

    override fun reject(
        skillId: String,
        note: String?,
    ): ProceduralSkillArtifact? {
        val normalizedSkillId = skillId.trim()
        if (normalizedSkillId.isBlank()) return null

        synchronized(lock) {
            return withConnection { connection ->
                val target = findById(connection, normalizedSkillId) ?: return@withConnection null
                if (target.status != ProceduralSkillStatus.PENDING_APPROVAL) return@withConnection null

                val now = Instant.now().toString()
                val decisionNote = note?.trim()?.ifBlank { null }
                val rejected =
                    if (target.pendingOperation == ProceduralSkillOperation.ARCHIVE) {
                        target.copy(
                            status = ProceduralSkillStatus.ACTIVE,
                            pendingOperation = null,
                            decisionNote = decisionNote,
                            rejectedAt = now,
                            updatedAt = now,
                        )
                    } else {
                        target.copy(
                            status = ProceduralSkillStatus.REJECTED,
                            pendingOperation = null,
                            decisionNote = decisionNote,
                            rejectedAt = now,
                            updatedAt = now,
                        )
                    }
                updateEntry(connection, rejected)
                rejected
            }
        }
    }

    override fun list(
        status: ProceduralSkillStatus?,
        limit: Int,
    ): List<ProceduralSkillArtifact> {
        val bounded = limit.coerceIn(1, 10_000)
        synchronized(lock) {
            return withConnection { connection ->
                val scope = currentScope.get()
                val primary = queryByScope(connection, scope, status, bounded)
                if (primary.isNotEmpty()) {
                    primary
                } else {
                    val legacyScope = legacyScopeAlias.get()
                    val legacy = queryByScope(connection, legacyScope, status, bounded)
                    if (legacy.isNotEmpty() && legacyScope != scope) {
                        println("Warning: procedural skill store loaded legacy scoped rows for scope_key='$legacyScope' because normalized scope_key='$scope' was not found.")
                    }
                    legacy
                }
            }
        }
    }

    override fun get(skillId: String): ProceduralSkillArtifact? {
        val normalizedSkillId = skillId.trim()
        if (normalizedSkillId.isBlank()) return null

        synchronized(lock) {
            return withConnection { connection -> findById(connection, normalizedSkillId) }
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

    private fun applySupersedeSideEffect(
        connection: Connection,
        approved: ProceduralSkillArtifact,
        now: String,
    ) {
        val supersededId = approved.supersedesSkillId ?: return
        val current = findById(connection, supersededId) ?: return
        if (current.status == ProceduralSkillStatus.ACTIVE) {
            updateEntry(
                connection,
                current.copy(
                    status = ProceduralSkillStatus.SUPERSEDED,
                    supersededBySkillId = approved.skillId,
                    updatedAt = now,
                ),
            )
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
                        skill_id TEXT NOT NULL,
                        slug TEXT NOT NULL,
                        status TEXT NOT NULL,
                        pending_operation TEXT,
                        version INTEGER NOT NULL,
                        supersedes_skill_id TEXT,
                        superseded_by_skill_id TEXT,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        payload TEXT NOT NULL,
                        UNIQUE (scope_key, skill_id)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    "CREATE INDEX IF NOT EXISTS ${tableName}_scope_status_idx ON $tableName (scope_key, status, id)",
                )
            }
        }
    }

    private fun insertEntry(
        connection: Connection,
        entry: ProceduralSkillArtifact,
    ) {
        val sql =
            """
            INSERT INTO $tableName (
                scope_key,
                skill_id,
                slug,
                status,
                pending_operation,
                version,
                supersedes_skill_id,
                superseded_by_skill_id,
                payload
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, currentScope.get())
            statement.setString(2, entry.skillId)
            statement.setString(3, entry.slug)
            statement.setString(4, entry.status.name)
            statement.setString(5, entry.pendingOperation?.name)
            statement.setInt(6, entry.version)
            statement.setString(7, entry.supersedesSkillId)
            statement.setString(8, entry.supersededBySkillId)
            statement.setString(9, gson.toJson(entry.normalized()))
            statement.executeUpdate()
        }
    }

    private fun updateEntry(
        connection: Connection,
        entry: ProceduralSkillArtifact,
    ) {
        val sql =
            """
            UPDATE $tableName
            SET slug = ?,
                status = ?,
                pending_operation = ?,
                version = ?,
                supersedes_skill_id = ?,
                superseded_by_skill_id = ?,
                updated_at = CURRENT_TIMESTAMP,
                payload = ?
            WHERE scope_key = ? AND skill_id = ?
            """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, entry.slug)
            statement.setString(2, entry.status.name)
            statement.setString(3, entry.pendingOperation?.name)
            statement.setInt(4, entry.version)
            statement.setString(5, entry.supersedesSkillId)
            statement.setString(6, entry.supersededBySkillId)
            statement.setString(7, gson.toJson(entry.normalized()))
            statement.setString(8, currentScope.get())
            statement.setString(9, entry.skillId)
            statement.executeUpdate()
        }
    }

    private fun findById(
        connection: Connection,
        skillId: String,
    ): ProceduralSkillArtifact? {
        val scope = currentScope.get()
        val primary = queryById(connection, scope, skillId)
        if (primary != null) return primary

        val legacyScope = legacyScopeAlias.get()
        val legacy = queryById(connection, legacyScope, skillId)
        if (legacy != null && legacyScope != scope) {
            println("Warning: procedural skill store loaded legacy scoped row for scope_key='$legacyScope' because normalized scope_key='$scope' was not found.")
        }
        return legacy
    }

    private fun queryById(
        connection: Connection,
        scopeKey: String,
        skillId: String,
    ): ProceduralSkillArtifact? {
        val sql = "SELECT payload FROM $tableName WHERE scope_key = ? AND skill_id = ? LIMIT 1"
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, scopeKey)
            statement.setString(2, skillId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) return null
                return parseProceduralSkillArtifact(rs.getString("payload"), gson)
            }
        }
    }

    private fun queryByScope(
        connection: Connection,
        scopeKey: String,
        status: ProceduralSkillStatus?,
        limit: Int,
    ): List<ProceduralSkillArtifact> {
        val sql =
            if (status == null) {
                "SELECT payload FROM $tableName WHERE scope_key = ? ORDER BY id ASC LIMIT ?"
            } else {
                "SELECT payload FROM $tableName WHERE scope_key = ? AND status = ? ORDER BY id ASC LIMIT ?"
            }
        return connection.prepareStatement(sql).use { statement ->
            statement.setString(1, scopeKey)
            if (status == null) {
                statement.setInt(2, limit)
            } else {
                statement.setString(2, status.name)
                statement.setInt(3, limit)
            }
            statement.executeQuery().use { rs -> parseProceduralSkillRows(rs, gson) }
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

private fun parseProceduralSkillRows(
    rs: ResultSet,
    gson: Gson,
): List<ProceduralSkillArtifact> {
    val rows = mutableListOf<ProceduralSkillArtifact>()
    while (rs.next()) {
        val parsed = parseProceduralSkillArtifact(rs.getString("payload"), gson)
        if (parsed != null) {
            rows += parsed
        }
    }
    return rows
}

private fun parseProceduralSkillArtifact(
    payload: String,
    gson: Gson,
): ProceduralSkillArtifact? =
    runCatching {
        gson.fromJson(payload, ProceduralSkillArtifact::class.java)?.normalized()
    }.getOrNull()

internal object ProceduralSkillStoreFactory {
    fun create(persistenceConfiguration: PersistenceRuntimeConfiguration): ProceduralSkillStore {
        val normalizedBackend = persistenceConfiguration.backend.lowercase()
        return when (normalizedBackend) {
            "jdbc", "postgres", "postgresql" -> {
                val jdbcUrl =
                    requireNotNull(persistenceConfiguration.jdbcUrl) {
                        "BERTBOT_STATE_JDBC_URL must be set when BERTBOT_STATE_STORE is '$normalizedBackend'."
                    }
                JdbcProceduralSkillStore(
                    jdbcUrl = jdbcUrl,
                    username = persistenceConfiguration.jdbcUser,
                    password = persistenceConfiguration.jdbcPassword,
                    tableName = persistenceConfiguration.proceduralSkillJdbcTable,
                )
            }
            else -> FileProceduralSkillStore(file = File(persistenceConfiguration.proceduralSkillFilePath))
        }
    }
}

private fun ProceduralSkillCreateRequest.normalized(): ProceduralSkillCreateRequest =
    copy(
        slug = slug.normalizeSkillSlug(),
        title = title.normalizeSkillTitle(),
        instructions = instructions.normalizeSkillInstructions(),
    )

private fun ProceduralSkillPatchRequest.normalized(): ProceduralSkillPatchRequest =
    copy(
        title = title?.normalizeSkillTitle(),
        instructions = instructions?.normalizeSkillInstructions(),
    )

private fun ProceduralSkillSupersedeRequest.normalized(): ProceduralSkillSupersedeRequest =
    copy(
        slug = slug.normalizeSkillSlug(),
        title = title.normalizeSkillTitle(),
        instructions = instructions.normalizeSkillInstructions(),
    )

private fun ProceduralSkillArtifact.normalized(): ProceduralSkillArtifact =
    copy(
        slug = slug.normalizeSkillSlug(),
        title = title.normalizeSkillTitle(),
        instructions = instructions.normalizeSkillInstructions(),
    )

private fun String.normalizeSkillSlug(): String =
    trim()
        .ifBlank { "skill" }
        .lowercase()
        .replace(Regex("[^a-z0-9_-]+"), "-")
        .trim('-')
        .ifBlank { "skill" }
        .take(96)

private fun String.normalizeSkillTitle(): String = trim().ifBlank { "Untitled skill" }.take(120)

private fun String.normalizeSkillInstructions(): String = trim().ifBlank { "(no instructions)" }.take(20_000)
