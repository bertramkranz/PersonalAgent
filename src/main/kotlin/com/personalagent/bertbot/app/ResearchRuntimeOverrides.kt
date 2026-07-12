package com.personalagent.bertbot.app

import com.personalagent.bertbot.config.BertBotAgentConfig
import com.personalagent.bertbot.config.ContinuousImprovementResearchConfig

internal fun applyResearchRuntimeOverrides(
    config: BertBotAgentConfig,
    environment: Map<String, String>,
    dotEnvValues: Map<String, String>,
): BertBotAgentConfig {
    val research = config.research

    val enabled = resolveBooleanOverride("BERTBOT_RESEARCH_ENABLED", environment, dotEnvValues) ?: research.enabled
    val eventDrivenEnabled =
        resolveBooleanOverride("BERTBOT_RESEARCH_EVENT_DRIVEN_ENABLED", environment, dotEnvValues) ?: research.eventDrivenEnabled
    val periodicEnabled =
        resolveBooleanOverride("BERTBOT_RESEARCH_PERIODIC_ENABLED", environment, dotEnvValues) ?: research.periodicEnabled
    val llmAssistedEnabled =
        resolveBooleanOverride("BERTBOT_RESEARCH_LLM_ASSISTED_ENABLED", environment, dotEnvValues) ?: research.llmAssistedEnabled
    val includeExternalSignals =
        resolveBooleanOverride("BERTBOT_RESEARCH_INCLUDE_EXTERNAL_SIGNALS", environment, dotEnvValues) ?: research.includeExternalSignals
    val periodicIntervalSeconds =
        resolveLongOverride("BERTBOT_RESEARCH_PERIODIC_INTERVAL_SECONDS", environment, dotEnvValues)?.coerceAtLeast(1)
            ?: research.periodicIntervalSeconds
    val minIntervalBetweenRunsSeconds =
        resolveLongOverride("BERTBOT_RESEARCH_MIN_INTERVAL_SECONDS", environment, dotEnvValues)?.coerceAtLeast(0)
            ?: research.minIntervalBetweenRunsSeconds
    val maxRecommendationsPerCycle =
        resolveIntOverride("BERTBOT_RESEARCH_MAX_RECOMMENDATIONS_PER_CYCLE", environment, dotEnvValues)?.coerceAtLeast(1)
            ?: research.maxRecommendationsPerCycle
    val failureCooldownSeconds =
        resolveLongOverride("BERTBOT_RESEARCH_FAILURE_COOLDOWN_SECONDS", environment, dotEnvValues)?.coerceAtLeast(0)
            ?: research.failureCooldownSeconds

    val resolvedResearch =
        ContinuousImprovementResearchConfig(
            enabled = enabled,
            eventDrivenEnabled = eventDrivenEnabled,
            periodicEnabled = periodicEnabled,
            llmAssistedEnabled = llmAssistedEnabled,
            includeExternalSignals = includeExternalSignals,
            periodicIntervalSeconds = periodicIntervalSeconds,
            minIntervalBetweenRunsSeconds = minIntervalBetweenRunsSeconds,
            maxRecommendationsPerCycle = maxRecommendationsPerCycle,
            failureCooldownSeconds = failureCooldownSeconds,
        )

    return config.copy(research = resolvedResearch)
}

private fun resolveBooleanOverride(
    name: String,
    environment: Map<String, String>,
    dotEnvValues: Map<String, String>,
): Boolean? {
    val raw = resolveRuntimeSetting(name, environment, dotEnvValues) ?: return null
    return when (raw.lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> null
    }
}

private fun resolveIntOverride(
    name: String,
    environment: Map<String, String>,
    dotEnvValues: Map<String, String>,
): Int? = resolveRuntimeSetting(name, environment, dotEnvValues)?.toIntOrNull()

private fun resolveLongOverride(
    name: String,
    environment: Map<String, String>,
    dotEnvValues: Map<String, String>,
): Long? = resolveRuntimeSetting(name, environment, dotEnvValues)?.toLongOrNull()
