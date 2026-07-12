package com.personalagent.bertbot.app

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.personalagent.bertbot.config.BertBotAgentConfig
import com.personalagent.bertbot.graph.runtime.TraceLogger
import com.personalagent.bertbot.graph.runtime.TracingContext
import com.personalagent.bertbot.llm.LlmGateway
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

internal enum class ResearchTrigger {
    EVENT,
    PERIODIC,
    MANUAL,
}

internal enum class RecommendationStatus {
    NEW,
    ACCEPTED,
    REJECTED,
    STALE,
}

internal data class ImprovementRecommendation(
    val key: String,
    val title: String,
    val category: String,
    val rationale: String,
    val evidence: List<String>,
    val impact: Int,
    val effort: Int,
    val confidence: Int,
    val score: Double,
    val status: RecommendationStatus = RecommendationStatus.NEW,
    val createdAt: String,
    val updatedAt: String,
    val lastSeenAt: String,
)

internal data class ResearchCycleReport(
    val trigger: ResearchTrigger,
    val executed: Boolean,
    val skippedReason: String? = null,
    val recommendationCount: Int = 0,
    val upsertedCount: Int = 0,
    val timestamp: String = Instant.now().toString(),
)

internal interface ImprovementRecommendationStore {
    fun list(): List<ImprovementRecommendation>

    fun upsert(recommendations: List<ImprovementRecommendation>): Int
}

internal class FileImprovementRecommendationStore(
    private val storageFile: File,
    private val gson: Gson = Gson(),
) : ImprovementRecommendationStore {
    private val lock = Any()
    private var cached: MutableList<ImprovementRecommendation> = mutableListOf()

    init {
        cached = loadPersisted().toMutableList()
    }

    override fun list(): List<ImprovementRecommendation> =
        synchronized(lock) {
            cached.toList()
        }

    override fun upsert(recommendations: List<ImprovementRecommendation>): Int {
        if (recommendations.isEmpty()) {
            return 0
        }

        synchronized(lock) {
            val now = Instant.now().toString()
            var changes = 0
            recommendations.forEach { recommendation ->
                val index = cached.indexOfFirst { existing -> existing.key == recommendation.key }
                if (index >= 0) {
                    val existing = cached[index]
                    val merged =
                        recommendation.copy(
                            createdAt = existing.createdAt,
                            status = existing.status,
                            updatedAt = now,
                            lastSeenAt = now,
                        )
                    if (merged != existing) {
                        cached[index] = merged
                        changes += 1
                    }
                } else {
                    cached.add(
                        recommendation.copy(
                            createdAt = now,
                            updatedAt = now,
                            lastSeenAt = now,
                        ),
                    )
                    changes += 1
                }
            }
            persist()
            return changes
        }
    }

    private fun loadPersisted(): List<ImprovementRecommendation> {
        synchronized(lock) {
            if (!storageFile.exists()) {
                return emptyList()
            }

            val content = storageFile.readText().trim()
            if (content.isBlank()) {
                return emptyList()
            }

            return try {
                val payload = gson.fromJson(content, PersistedRecommendations::class.java)
                if (payload.schemaVersion != 1) {
                    emptyList()
                } else {
                    payload.recommendations
                }
            } catch (_: JsonSyntaxException) {
                preserveUnreadableFile(storageFile)
                emptyList()
            }
        }
    }

    private fun persist() {
        writeTextAtomically(
            target = storageFile,
            content = gson.toJson(PersistedRecommendations(recommendations = cached.toList())),
        )
    }
}

internal class ContinuousImprovementResearchService(
    private val config: BertBotAgentConfig,
    private val workspaceRoot: File,
    private val store: ImprovementRecommendationStore,
    private val llmGateway: LlmGateway? = null,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val workspaceInspector = ResearchWorkspaceInspector(workspaceRoot)
    private var activeRun: Boolean = false
    private var lastRunAtMillis: Long = 0
    private var lastFailureAtMillis: Long = 0
    private var consecutiveFailures: Int = 0
    private var lastReport: ResearchCycleReport? = null

    fun listRecommendations(
        limit: Int,
        category: String? = null,
    ): List<ImprovementRecommendation> {
        val normalizedCategory = category?.trim()?.lowercase()
        return store
            .list()
            .asSequence()
            .filter { recommendation ->
                normalizedCategory.isNullOrBlank() || recommendation.category.lowercase() == normalizedCategory
            }.sortedWith(compareByDescending<ImprovementRecommendation> { it.score }.thenBy { it.key })
            .take(limit.coerceIn(1, 100))
            .toList()
    }

    fun lastCycleReport(): ResearchCycleReport? = synchronized(lock) { lastReport }

    fun maybeRunEvent(reason: String): ResearchCycleReport = runCycle(ResearchTrigger.EVENT, reason, bypassMinInterval = false)

    fun maybeRunPeriodic(reason: String): ResearchCycleReport = runCycle(ResearchTrigger.PERIODIC, reason, bypassMinInterval = false)

    fun runNow(reason: String): ResearchCycleReport = runCycle(ResearchTrigger.MANUAL, reason, bypassMinInterval = true)

    private fun runCycle(
        trigger: ResearchTrigger,
        reason: String,
        bypassMinInterval: Boolean,
    ): ResearchCycleReport {
        val gatingResult = beforeRun(trigger, bypassMinInterval)
        if (gatingResult != null) {
            return gatingResult
        }

        val traceContext = TracingContext()
        TraceLogger.info(traceContext, "research_cycle_started", "trigger=${trigger.name.lowercase()} reason=$reason")
        return runCatching {
            val recommendations = buildRecommendations().take(config.research.maxRecommendationsPerCycle)
            val upserted = store.upsert(recommendations)
            TraceLogger.info(
                traceContext,
                "research_cycle_completed",
                "trigger=${trigger.name.lowercase()} recommendation_count=${recommendations.size} upserted_count=$upserted",
            )
            ResearchCycleReport(
                trigger = trigger,
                executed = true,
                recommendationCount = recommendations.size,
                upsertedCount = upserted,
            )
        }.fold(
            onSuccess = { report ->
                synchronized(lock) {
                    activeRun = false
                    consecutiveFailures = 0
                    lastRunAtMillis = nowMillis()
                    lastReport = report
                }
                report
            },
            onFailure = { error ->
                TraceLogger.warn(
                    traceContext,
                    "research_cycle_failed",
                    "trigger=${trigger.name.lowercase()} message=${error.message ?: "unknown"}",
                )
                val failed =
                    ResearchCycleReport(
                        trigger = trigger,
                        executed = false,
                        skippedReason = "cycle_failed:${error.message ?: "unknown"}",
                    )
                synchronized(lock) {
                    activeRun = false
                    consecutiveFailures += 1
                    lastFailureAtMillis = nowMillis()
                    lastReport = failed
                }
                failed
            },
        )
    }

    private fun beforeRun(
        trigger: ResearchTrigger,
        bypassMinInterval: Boolean,
    ): ResearchCycleReport? {
        val now = nowMillis()
        synchronized(lock) {
            if (!config.research.enabled) {
                return skipped(trigger, "research_disabled")
            }
            if (trigger == ResearchTrigger.EVENT && !config.research.eventDrivenEnabled) {
                return skipped(trigger, "event_driven_disabled")
            }
            if (trigger == ResearchTrigger.PERIODIC && !config.research.periodicEnabled) {
                return skipped(trigger, "periodic_disabled")
            }
            if (activeRun) {
                return skipped(trigger, "run_in_progress")
            }
            if (!bypassMinInterval && lastRunAtMillis > 0) {
                val intervalMillis = config.research.minIntervalBetweenRunsSeconds * 1000
                if (now - lastRunAtMillis < intervalMillis) {
                    return skipped(trigger, "min_interval_not_elapsed")
                }
            }
            if (consecutiveFailures > 0) {
                val cooldownMillis = config.research.failureCooldownSeconds * 1000
                if (cooldownMillis > 0 && now - lastFailureAtMillis < cooldownMillis) {
                    return skipped(trigger, "failure_cooldown_active")
                }
            }
            activeRun = true
            return null
        }
    }

    private fun skipped(
        trigger: ResearchTrigger,
        reason: String,
    ): ResearchCycleReport {
        val report =
            ResearchCycleReport(
                trigger = trigger,
                executed = false,
                skippedReason = reason,
            )
        lastReport = report
        return report
    }

    private fun buildRecommendations(): List<ImprovementRecommendation> {
        val now = Instant.now().toString()
        val enabledSubAgentIds = config.enabledSubAgents().map { definition -> definition.id }.toSet()
        val enabledToolNames = config.enabledTools().map { tool -> tool.name }.toSet()
        val recommendations = mutableListOf<RecommendationDraft>()

        recommendations += configDrivenDrafts(enabledSubAgentIds, enabledToolNames)
        recommendations += workspaceDrivenDrafts()
        recommendations += llmAssistedDrafts()
        recommendations += externalSignalDrafts()

        if (recommendations.isEmpty()) {
            recommendations +=
                RecommendationDraft(
                    key = "kotlin.maintenance_review_cycle",
                    title = "Schedule regular Kotlin/MCP/AI architecture review cycles",
                    category = "kotlin",
                    rationale = "Continuous architecture review preserves maintainability as capabilities grow.",
                    evidence = listOf("signal=no high-priority structural gaps detected"),
                    impact = 3,
                    effort = 1,
                    confidence = 3,
                )
        }

        return recommendations
            .map { draft -> recommendation(draft, now) }
            .deduplicateRecommendationsByKey()
            .sortedByDescending { recommendation -> recommendation.score }
    }

    private fun llmAssistedDrafts(): List<RecommendationDraft> {
        if (!config.research.llmAssistedEnabled) {
            return emptyList()
        }

        val gateway = llmGateway ?: return emptyList()
        val workspaceSnapshot = workspaceInspector.snapshot()
        val tracingContext = TracingContext()

        return ResearchLlmDraftGenerator
            .generate(
                llmGateway = gateway,
                workspaceSnapshot = workspaceSnapshot,
                tracingContext = tracingContext,
            ).map { candidate ->
                RecommendationDraft(
                    key = candidate.key,
                    title = candidate.title,
                    category = candidate.category,
                    rationale = candidate.rationale,
                    evidence = candidate.evidence,
                    impact = candidate.impact,
                    effort = candidate.effort,
                    confidence = candidate.confidence,
                )
            }
    }

    private fun externalSignalDrafts(): List<RecommendationDraft> {
        if (!config.research.includeExternalSignals) {
            return emptyList()
        }

        return listOf(
            RecommendationDraft(
                key = "ai.koog_beta_module_watch",
                title = "Track Koog beta modules and pin upgrade policy in docs",
                category = "ai",
                rationale = "MCP and planner integrations can evolve quickly in beta; explicit upgrade policy reduces breakage risk.",
                evidence = listOf("signal=koog_module_versioning_best_practice"),
                impact = 4,
                effort = 2,
                confidence = 4,
            ),
        )
    }

    private fun configDrivenDrafts(
        enabledSubAgentIds: Set<String>,
        enabledToolNames: Set<String>,
    ): List<RecommendationDraft> {
        val drafts = mutableListOf<RecommendationDraft>()

        if (!enabledSubAgentIds.contains("repo_improvement_researcher")) {
            drafts +=
                RecommendationDraft(
                    key = "agent.repo_improvement_researcher",
                    title = "Add dedicated repo improvement researcher sub-agent",
                    category = "ai",
                    rationale = "A focused sub-agent improves repeatability of capability scouting and backlog hygiene.",
                    evidence = listOf("enabled_sub_agents=${enabledSubAgentIds.sorted().joinToString()}"),
                    impact = 5,
                    effort = 2,
                    confidence = 5,
                )
        }

        if (!enabledToolNames.contains("agent.broadcast")) {
            drafts +=
                RecommendationDraft(
                    key = "productivity.parallel_delegation",
                    title = "Enable parallel delegation for independent analysis tracks",
                    category = "productivity",
                    rationale = "Parallel delegation reduces cycle time for planning + risk validation workflows.",
                    evidence = listOf("enabled_tools=${enabledToolNames.sorted().joinToString()}"),
                    impact = 4,
                    effort = 2,
                    confidence = 4,
                )
        }

        return drafts
    }

    private fun workspaceDrivenDrafts(): List<RecommendationDraft> {
        val drafts = mutableListOf<RecommendationDraft>()

        val codeQlWorkflow = File(workspaceRoot, ".github/workflows/codeql.yml")
        if (!codeQlWorkflow.exists()) {
            drafts +=
                RecommendationDraft(
                    key = "security.codeql_required",
                    title = "Add CodeQL workflow for static security analysis",
                    category = "security",
                    rationale = "CodeQL catches vulnerable patterns early and strengthens merge gates.",
                    evidence = listOf("missing_file=.github/workflows/codeql.yml"),
                    impact = 5,
                    effort = 2,
                    confidence = 5,
                )
        }

        val hasPromptSecurityTests = workspaceInspector.containsFileName("PromptSecurityTest")
        if (!hasPromptSecurityTests) {
            drafts +=
                RecommendationDraft(
                    key = "security.prompt_abuse_regression_tests",
                    title = "Add prompt/tool abuse regression tests",
                    category = "security",
                    rationale = "Regression tests help lock in tool-honesty and prompt injection safeguards.",
                    evidence = listOf("signal=PromptSecurityTest missing"),
                    impact = 4,
                    effort = 3,
                    confidence = 4,
                )
        }

        val hasMcpContractTests = workspaceInspector.containsFileName("McpRequestDispatcherTest")
        if (!hasMcpContractTests) {
            drafts +=
                RecommendationDraft(
                    key = "mcp.contract_tests",
                    title = "Add MCP contract tests for tool schema and invalid argument handling",
                    category = "mcp",
                    rationale = "Contract tests reduce breakage risk when evolving tool APIs.",
                    evidence = listOf("signal=McpRequestDispatcherTest missing"),
                    impact = 4,
                    effort = 3,
                    confidence = 4,
                )
        }

        val hasPerformanceSuite = workspaceInspector.containsToken("benchmark") || workspaceInspector.containsToken("performance")
        if (!hasPerformanceSuite) {
            drafts +=
                RecommendationDraft(
                    key = "performance.baseline_suite",
                    title = "Introduce a lightweight performance baseline suite",
                    category = "performance",
                    rationale = "A baseline suite helps detect latency and cost regressions in AI and MCP flows.",
                    evidence = listOf("signal=no benchmark/performance suite marker found"),
                    impact = 4,
                    effort = 3,
                    confidence = 3,
                )
        }

        return drafts
    }

    private fun recommendation(
        draft: RecommendationDraft,
        now: String,
    ): ImprovementRecommendation {
        val cappedImpact = draft.impact.coerceIn(1, 5)
        val cappedEffort = draft.effort.coerceIn(1, 5)
        val cappedConfidence = draft.confidence.coerceIn(1, 5)
        val score = (cappedImpact.toDouble() * cappedConfidence.toDouble()) / cappedEffort.toDouble()
        return ImprovementRecommendation(
            key = draft.key,
            title = draft.title,
            category = draft.category,
            rationale = draft.rationale,
            evidence = draft.evidence,
            impact = cappedImpact,
            effort = cappedEffort,
            confidence = cappedConfidence,
            score = score,
            createdAt = now,
            updatedAt = now,
            lastSeenAt = now,
        )
    }
}

private data class RecommendationDraft(
    val key: String,
    val title: String,
    val category: String,
    val rationale: String,
    val evidence: List<String>,
    val impact: Int,
    val effort: Int,
    val confidence: Int,
)

internal class ContinuousImprovementResearchScheduler(
    private val service: ContinuousImprovementResearchService,
    intervalSeconds: Long,
) : AutoCloseable {
    private val scheduler =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(
                runnable,
                "bertbot-research-scheduler",
            ).apply {
                isDaemon = true
            }
        }

    init {
        val cadence = max(1L, intervalSeconds)
        scheduler.scheduleWithFixedDelay(
            {
                runCatching {
                    service.maybeRunPeriodic("scheduled_interval")
                }
            },
            cadence,
            cadence,
            TimeUnit.SECONDS,
        )
    }

    override fun close() {
        scheduler.shutdownNow()
        runCatching { scheduler.awaitTermination(1, TimeUnit.SECONDS) }
    }
}

private data class PersistedRecommendations(
    val schemaVersion: Int = 1,
    val recommendations: List<ImprovementRecommendation> = emptyList(),
)

private fun List<ImprovementRecommendation>.deduplicateRecommendationsByKey(): List<ImprovementRecommendation> {
    val merged = linkedMapOf<String, ImprovementRecommendation>()
    forEach { recommendation ->
        val current = merged[recommendation.key]
        if (current == null || recommendation.score > current.score) {
            merged[recommendation.key] = recommendation
        }
    }
    return merged.values.toList()
}

private fun writeTextAtomically(
    target: File,
    content: String,
) {
    val parentDir = target.parentFile ?: File(".")
    parentDir.mkdirs()
    val tempPath = Files.createTempFile(parentDir.toPath(), "${target.nameWithoutExtension}-", ".tmp")
    val tempFile = tempPath.toFile()
    try {
        tempFile.writeText(content)
        try {
            Files.move(tempPath, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tempPath, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } catch (e: Exception) {
        runCatching { tempFile.delete() }
        throw e
    }
}

private fun preserveUnreadableFile(storageFile: File) {
    val extension = storageFile.extension.takeIf { it.isNotBlank() } ?: "json"
    val backupFile = File(storageFile.parentFile ?: File("."), "${storageFile.nameWithoutExtension}.corrupt-${System.currentTimeMillis()}.$extension")
    runCatching {
        Files.copy(storageFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
