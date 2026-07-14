package com.personalagent.bertbot.app

import com.openai.models.ChatModel
import com.personalagent.bertbot.config.BertBotAgentConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiRuntimeConfigurationTest {
    @Test
    fun `resolve configuration uses explicit environment values before dotenv`() {
        val configuration =
            resolveAiRuntimeConfiguration(
                environment =
                    mapOf(
                        "BERTBOT_AI_PROVIDER" to "openai",
                        "BERTBOT_AI_MODEL" to "gpt-4o",
                        "BERTBOT_AI_API_KEY" to "env-key",
                        "BERTBOT_OLLAMA_BASE_URL" to "http://env-ollama:11434",
                        "BERTBOT_OLLAMA_TIMEOUT_SECONDS" to "180",
                    ),
                dotEnvValues =
                    mapOf(
                        "BERTBOT_AI_PROVIDER" to "ignored",
                        "BERTBOT_AI_MODEL" to "ignored",
                        "BERTBOT_AI_API_KEY" to "dotenv-key",
                        "BERTBOT_OLLAMA_BASE_URL" to "http://dotenv-ollama:11434",
                        "BERTBOT_OLLAMA_TIMEOUT_SECONDS" to "300",
                    ),
            )

        assertEquals("openai", configuration.provider)
        assertEquals("gpt-4o", configuration.model)
        assertEquals("env-key", configuration.apiKey)
        assertEquals("http://env-ollama:11434", configuration.ollamaBaseUrl)
        assertEquals(180, configuration.ollamaTimeoutSeconds)
    }

    @Test
    fun `resolve configuration falls back to dotenv values`() {
        val configuration =
            resolveAiRuntimeConfiguration(
                environment = emptyMap(),
                dotEnvValues =
                    mapOf(
                        "BERTBOT_AI_PROVIDER" to "openai",
                        "BERTBOT_AI_MODEL" to "gpt-4o-mini",
                        "BERTBOT_AI_API_KEY" to "dotenv-key",
                        "BERTBOT_OLLAMA_BASE_URL" to "http://dotenv-ollama:11434",
                        "BERTBOT_OLLAMA_TIMEOUT_SECONDS" to "95",
                    ),
            )

        assertEquals("openai", configuration.provider)
        assertEquals("gpt-4o-mini", configuration.model)
        assertEquals("dotenv-key", configuration.apiKey)
        assertEquals("http://dotenv-ollama:11434", configuration.ollamaBaseUrl)
        assertEquals(95, configuration.ollamaTimeoutSeconds)
    }

    @Test
    fun `resolve configuration uses defaults when no overrides are provided`() {
        val configuration =
            resolveAiRuntimeConfiguration(
                environment = emptyMap(),
                dotEnvValues = emptyMap(),
            )

        assertEquals(DEFAULT_AI_PROVIDER, configuration.provider)
        assertEquals(DEFAULT_AI_MODEL, configuration.model)
        assertNull(configuration.apiKey)
        assertEquals(DEFAULT_OLLAMA_BASE_URL, configuration.ollamaBaseUrl)
        assertEquals(DEFAULT_OLLAMA_TIMEOUT_SECONDS, configuration.ollamaTimeoutSeconds)
    }

    @Test
    fun `openai model resolution preserves configured model name`() {
        val model = resolveOpenAiChatModel("gpt-4o")

        assertEquals(ChatModel.GPT_4O, model)
        assertEquals("gpt-4o", model.asString())
    }

    @Test
    fun `persistence configuration defaults to file backend`() {
        val configuration =
            resolvePersistenceRuntimeConfiguration(
                environment = emptyMap(),
                dotEnvValues = emptyMap(),
            )

        assertEquals(DEFAULT_PERSISTENCE_BACKEND, configuration.backend)
        assertEquals(DEFAULT_JSON_CODEC, configuration.jsonCodec)
        assertEquals(DEFAULT_STATE_FILE_PATH, configuration.stateFilePath)
        assertEquals(DEFAULT_CHECKPOINT_FILE_PATH, configuration.checkpointFilePath)
        assertEquals(DEFAULT_CHECKPOINT_AUTOSAVE_ENABLED, configuration.checkpointAutoSaveEnabled)
        assertEquals(DEFAULT_EVENT_SOURCING_ENABLED, configuration.eventSourcingEnabled)
        assertEquals(DEFAULT_STATE_EVENT_FILE_PATH, configuration.stateEventFilePath)
        assertEquals(DEFAULT_EPISODIC_MEMORY_FILE_PATH, configuration.episodicMemoryFilePath)
        assertEquals(DEFAULT_SEMANTIC_MEMORY_FILE_PATH, configuration.semanticMemoryFilePath)
        assertEquals(DEFAULT_PROFILE_FILE_PATH, configuration.profileFilePath)
        assertEquals(DEFAULT_INGESTION_CONSENT_FILE_PATH, configuration.ingestionConsentFilePath)
        assertEquals(DEFAULT_INGESTION_SOURCE_STATE_FILE_PATH, configuration.ingestionSourceStateFilePath)
        assertEquals(DEFAULT_RESEARCH_RECOMMENDATIONS_FILE_PATH, configuration.researchRecommendationsFilePath)
        assertEquals(DEFAULT_STATE_JDBC_TABLE, configuration.jdbcTable)
        assertEquals(DEFAULT_EPISODIC_MEMORY_JDBC_TABLE, configuration.episodicMemoryJdbcTable)
        assertEquals(DEFAULT_STATE_EVENT_JDBC_TABLE, configuration.stateEventJdbcTable)
        assertEquals(DEFAULT_SEMANTIC_MEMORY_JDBC_TABLE, configuration.semanticMemoryJdbcTable)
        assertEquals(DEFAULT_PROFILE_JDBC_TABLE, configuration.profileJdbcTable)
        assertEquals(DEFAULT_INGESTION_CONSENT_JDBC_TABLE, configuration.ingestionConsentJdbcTable)
        assertEquals(DEFAULT_INGESTION_SOURCE_STATE_JDBC_TABLE, configuration.ingestionSourceStateJdbcTable)
        assertNull(configuration.jdbcUrl)
    }

    @Test
    fun `persistence configuration prefers environment over dotenv`() {
        val configuration =
            resolvePersistenceRuntimeConfiguration(
                environment =
                    mapOf(
                        "BERTBOT_STATE_STORE" to "postgres",
                        "BERTBOT_JSON_CODEC" to "kotlinx",
                        "BERTBOT_STATE_JDBC_URL" to "jdbc:postgresql://localhost:5432/bertbot",
                        "BERTBOT_STATE_JDBC_USER" to "env-user",
                        "BERTBOT_STATE_JDBC_PASSWORD" to "env-pass",
                        "BERTBOT_STATE_JDBC_TABLE" to "bertbot_state_table",
                        "BERTBOT_CHECKPOINT_FILE_PATH" to "checkpoints-from-env.json",
                        "BERTBOT_CHECKPOINT_AUTOSAVE_ENABLED" to "true",
                        "BERTBOT_EVENT_SOURCING_ENABLED" to "true",
                        "BERTBOT_STATE_EVENT_FILE_PATH" to "events-from-env.json",
                        "BERTBOT_CHECKPOINT_JDBC_TABLE" to "checkpoint_table",
                        "BERTBOT_STATE_EVENT_JDBC_TABLE" to "state_event_table",
                        "BERTBOT_STATE_FILE_PATH" to "state-from-env.json",
                        "BERTBOT_MEMORY_EPISODIC_FILE_PATH" to "episodic-from-env.json",
                        "BERTBOT_MEMORY_SEMANTIC_FILE_PATH" to "semantic-from-env.json",
                        "BERTBOT_PROFILE_FILE_PATH" to "profile-from-env.json",
                        "BERTBOT_INGESTION_CONSENT_FILE_PATH" to "consent-from-env.json",
                        "BERTBOT_INGESTION_SOURCE_STATE_FILE_PATH" to "source-state-from-env.json",
                        "BERTBOT_RESEARCH_RECOMMENDATIONS_FILE_PATH" to "research-from-env.json",
                        "BERTBOT_MEMORY_EPISODIC_JDBC_TABLE" to "episodic_table",
                        "BERTBOT_MEMORY_SEMANTIC_JDBC_TABLE" to "semantic_table",
                        "BERTBOT_PROFILE_JDBC_TABLE" to "profile_table",
                        "BERTBOT_INGESTION_CONSENT_JDBC_TABLE" to "consent_table",
                        "BERTBOT_INGESTION_SOURCE_STATE_JDBC_TABLE" to "source_state_table",
                    ),
                dotEnvValues =
                    mapOf(
                        "BERTBOT_STATE_STORE" to "file",
                        "BERTBOT_STATE_FILE_PATH" to "state-from-dotenv.json",
                    ),
            )

        assertEquals("postgres", configuration.backend)
        assertEquals("kotlinx", configuration.jsonCodec)
        assertEquals("state-from-env.json", configuration.stateFilePath)
        assertEquals("checkpoints-from-env.json", configuration.checkpointFilePath)
        assertEquals(true, configuration.checkpointAutoSaveEnabled)
        assertEquals(true, configuration.eventSourcingEnabled)
        assertEquals("events-from-env.json", configuration.stateEventFilePath)
        assertEquals("episodic-from-env.json", configuration.episodicMemoryFilePath)
        assertEquals("semantic-from-env.json", configuration.semanticMemoryFilePath)
        assertEquals("profile-from-env.json", configuration.profileFilePath)
        assertEquals("consent-from-env.json", configuration.ingestionConsentFilePath)
        assertEquals("source-state-from-env.json", configuration.ingestionSourceStateFilePath)
        assertEquals("research-from-env.json", configuration.researchRecommendationsFilePath)
        assertEquals("jdbc:postgresql://localhost:5432/bertbot", configuration.jdbcUrl)
        assertEquals("env-user", configuration.jdbcUser)
        assertEquals("env-pass", configuration.jdbcPassword)
        assertEquals("bertbot_state_table", configuration.jdbcTable)
        assertEquals("checkpoint_table", configuration.checkpointJdbcTable)
        assertEquals("state_event_table", configuration.stateEventJdbcTable)
        assertEquals("episodic_table", configuration.episodicMemoryJdbcTable)
        assertEquals("semantic_table", configuration.semanticMemoryJdbcTable)
        assertEquals("profile_table", configuration.profileJdbcTable)
        assertEquals("consent_table", configuration.ingestionConsentJdbcTable)
        assertEquals("source_state_table", configuration.ingestionSourceStateJdbcTable)
    }

    @Test
    fun `macrofactor configuration defaults are applied`() {
        val configuration =
            resolveMacrofactorRuntimeConfiguration(
                environment = emptyMap(),
                dotEnvValues = emptyMap(),
            )

        assertEquals(DEFAULT_MACROFACTOR_ENABLED, configuration.enabled)
        assertEquals(DEFAULT_MACROFACTOR_COMMAND, configuration.command)
        assertEquals(DEFAULT_MACROFACTOR_ARGS, configuration.args)
        assertEquals(DEFAULT_MACROFACTOR_TIMEOUT_SECONDS, configuration.timeoutSeconds)
        assertEquals(DEFAULT_MACROFACTOR_TOOL_NAME_PREFIX, configuration.toolNamePrefix)
        assertNull(configuration.username)
        assertNull(configuration.password)
        assertEquals(false, configuration.isConfigured)
    }

    @Test
    fun `macrofactor configuration prefers environment over dotenv`() {
        val configuration =
            resolveMacrofactorRuntimeConfiguration(
                environment =
                    mapOf(
                        "BERTBOT_MACROFACTOR_ENABLED" to "true",
                        "BERTBOT_MACROFACTOR_COMMAND" to "node",
                        "BERTBOT_MACROFACTOR_ARGS" to "server.js,--stdio",
                        "BERTBOT_MACROFACTOR_USERNAME" to "env-user",
                        "BERTBOT_MACROFACTOR_PASSWORD" to "env-pass",
                        "BERTBOT_MACROFACTOR_TIMEOUT_SECONDS" to "90",
                        "BERTBOT_MACROFACTOR_TOOL_NAME_PREFIX" to "mf_",
                    ),
                dotEnvValues =
                    mapOf(
                        "BERTBOT_MACROFACTOR_ENABLED" to "false",
                        "BERTBOT_MACROFACTOR_USERNAME" to "dotenv-user",
                    ),
            )

        assertEquals(true, configuration.enabled)
        assertEquals("node", configuration.command)
        assertEquals(listOf("server.js", "--stdio"), configuration.args)
        assertEquals("env-user", configuration.username)
        assertEquals("env-pass", configuration.password)
        assertEquals(90, configuration.timeoutSeconds)
        assertEquals("mf_", configuration.toolNamePrefix)
        assertEquals(true, configuration.isConfigured)
    }

    @Test
    fun `macrofactor configuration supports explicit empty args override`() {
        val configuration =
            resolveMacrofactorRuntimeConfiguration(
                environment =
                    mapOf(
                        "BERTBOT_MACROFACTOR_COMMAND" to "macrofactor-mcp",
                        "BERTBOT_MACROFACTOR_ARGS" to "",
                    ),
                dotEnvValues = emptyMap(),
            )

        assertEquals("macrofactor-mcp", configuration.command)
        assertEquals(emptyList(), configuration.args)
    }

    @Test
    fun `shopping configuration prefers environment values for top-level settings`() {
        val configuration =
            resolveShoppingRuntimeConfiguration(
                environment =
                    mapOf(
                        "BERTBOT_SHOPPING_ENABLED" to "true",
                        "BERTBOT_SHOPPING_BUDGET_LIMIT_CENTS" to "3456",
                        "BERTBOT_SHOPPING_MIN_SELLER_TRUST_SCORE" to "0.8",
                        "BERTBOT_SHOPPING_STORE_1_ENABLED" to "true",
                    ),
                dotEnvValues =
                    mapOf(
                        "BERTBOT_SHOPPING_ENABLED" to "false",
                        "BERTBOT_SHOPPING_BUDGET_LIMIT_CENTS" to "9999",
                        "BERTBOT_SHOPPING_MIN_SELLER_TRUST_SCORE" to "0.2",
                    ),
            )

        assertEquals(true, configuration.enabled)
        assertEquals(3456L, configuration.budgetLimitCents)
        assertEquals(0.8, configuration.minSellerTrustScore)
        assertEquals(1, configuration.stores.size)
        assertEquals(true, configuration.stores.single().enabled)
    }

    @Test
    fun `google workspace configuration supports explicit empty args override`() {
        val configuration =
            resolveGoogleWorkspaceRuntimeConfiguration(
                environment =
                    mapOf(
                        "BERTBOT_GOOGLE_WORKSPACE_ENABLED" to "true",
                        "BERTBOT_GOOGLE_WORKSPACE_COMMAND" to "gemini-workspace-server",
                        "BERTBOT_GOOGLE_WORKSPACE_ARGS" to "",
                    ),
                dotEnvValues = emptyMap(),
            )

        assertEquals(true, configuration.enabled)
        assertEquals("gemini-workspace-server", configuration.command)
        assertEquals(emptyList(), configuration.args)
    }

    @Test
    fun `research runtime overrides prefer environment values`() {
        val config =
            applyResearchRuntimeOverrides(
                config = BertBotAgentConfig(),
                environment =
                    mapOf(
                        "BERTBOT_RESEARCH_ENABLED" to "false",
                        "BERTBOT_RESEARCH_EVENT_DRIVEN_ENABLED" to "false",
                        "BERTBOT_RESEARCH_PERIODIC_ENABLED" to "true",
                        "BERTBOT_RESEARCH_LLM_ASSISTED_ENABLED" to "true",
                        "BERTBOT_RESEARCH_INCLUDE_EXTERNAL_SIGNALS" to "true",
                        "BERTBOT_RESEARCH_PERIODIC_INTERVAL_SECONDS" to "120",
                        "BERTBOT_RESEARCH_MIN_INTERVAL_SECONDS" to "45",
                        "BERTBOT_RESEARCH_MAX_RECOMMENDATIONS_PER_CYCLE" to "12",
                        "BERTBOT_RESEARCH_FAILURE_COOLDOWN_SECONDS" to "90",
                    ),
                dotEnvValues = emptyMap(),
            )

        assertEquals(false, config.research.enabled)
        assertEquals(false, config.research.eventDrivenEnabled)
        assertEquals(true, config.research.periodicEnabled)
        assertEquals(true, config.research.llmAssistedEnabled)
        assertEquals(true, config.research.includeExternalSignals)
        assertEquals(120, config.research.periodicIntervalSeconds)
        assertEquals(45, config.research.minIntervalBetweenRunsSeconds)
        assertEquals(12, config.research.maxRecommendationsPerCycle)
        assertEquals(90, config.research.failureCooldownSeconds)
    }

    @Test
    fun `research runtime overrides fall back to defaults when invalid`() {
        val base = BertBotAgentConfig()
        val config =
            applyResearchRuntimeOverrides(
                config = base,
                environment =
                    mapOf(
                        "BERTBOT_RESEARCH_ENABLED" to "maybe",
                        "BERTBOT_RESEARCH_PERIODIC_INTERVAL_SECONDS" to "oops",
                        "BERTBOT_RESEARCH_MIN_INTERVAL_SECONDS" to "",
                        "BERTBOT_RESEARCH_MAX_RECOMMENDATIONS_PER_CYCLE" to "nan",
                    ),
                dotEnvValues = emptyMap(),
            )

        assertEquals(base.research.enabled, config.research.enabled)
        assertEquals(base.research.periodicIntervalSeconds, config.research.periodicIntervalSeconds)
        assertEquals(base.research.minIntervalBetweenRunsSeconds, config.research.minIntervalBetweenRunsSeconds)
        assertEquals(base.research.maxRecommendationsPerCycle, config.research.maxRecommendationsPerCycle)
    }

    @Test
    fun `checkpoint rollback policy defaults are applied`() {
        val configuration =
            resolveCheckpointRollbackPolicyConfiguration(
                environment = emptyMap(),
                dotEnvValues = emptyMap(),
            )

        assertEquals(DEFAULT_RUNTIME_ENVIRONMENT, configuration.environment)
        assertEquals(DEFAULT_CHECKPOINT_ROLLBACK_ENABLED, configuration.rollbackEnabled)
        assertEquals(DEFAULT_CHECKPOINT_ROLLBACK_REQUIRE_CONFIRM, configuration.requireConfirm)
        assertEquals(DEFAULT_CHECKPOINT_ROLLBACK_ALLOW_PROTECTED, configuration.allowInProtectedEnvironment)
        assertEquals(false, configuration.isProtectedEnvironment)
    }

    @Test
    fun `checkpoint rollback policy prefers environment over dotenv`() {
        val configuration =
            resolveCheckpointRollbackPolicyConfiguration(
                environment =
                    mapOf(
                        "BERTBOT_RUNTIME_ENV" to "production",
                        "BERTBOT_CHECKPOINT_ROLLBACK_ENABLED" to "true",
                        "BERTBOT_CHECKPOINT_ROLLBACK_REQUIRE_CONFIRM" to "false",
                        "BERTBOT_CHECKPOINT_ROLLBACK_ALLOW_PROTECTED" to "true",
                    ),
                dotEnvValues =
                    mapOf(
                        "BERTBOT_RUNTIME_ENV" to "dev",
                        "BERTBOT_CHECKPOINT_ROLLBACK_ENABLED" to "false",
                    ),
            )

        assertEquals("production", configuration.environment)
        assertEquals(true, configuration.rollbackEnabled)
        assertEquals(false, configuration.requireConfirm)
        assertEquals(true, configuration.allowInProtectedEnvironment)
        assertEquals(true, configuration.isProtectedEnvironment)
    }

    @Test
    fun `shopping configuration resolves global settings and store entries together`() {
        val configuration =
            resolveShoppingRuntimeConfiguration(
                environment =
                    mapOf(
                        "BERTBOT_SHOPPING_ENABLED" to "true",
                        "BERTBOT_SHOPPING_BUDGET_LIMIT_CENTS" to "25000",
                        "BERTBOT_SHOPPING_MIN_SELLER_TRUST_SCORE" to "0.8",
                        "BERTBOT_SHOPPING_STORE_1_ENABLED" to "true",
                        "BERTBOT_SHOPPING_STORE_1_PRIORITY" to "5",
                    ),
                dotEnvValues =
                    mapOf(
                        "BERTBOT_SHOPPING_ENABLED" to "false",
                        "BERTBOT_SHOPPING_STORE_1_PRIORITY" to "99",
                    ),
            )

        assertEquals(true, configuration.enabled)
        assertEquals(25000L, configuration.budgetLimitCents)
        assertEquals(0.8, configuration.minSellerTrustScore)
        assertEquals(1, configuration.stores.size)
        assertTrue(configuration.hasEnabledStore)
        assertEquals(5, configuration.stores.single().priority)
    }

    @Test
    fun `koog feature configuration defaults are applied`() {
        val configuration =
            resolveKoogFeatureRuntimeConfiguration(
                environment = emptyMap(),
                dotEnvValues = emptyMap(),
            )

        assertEquals(DEFAULT_KOOG_CHAT_MEMORY_ENABLED, configuration.chatMemoryEnabled)
        assertEquals(DEFAULT_KOOG_CHAT_MEMORY_WINDOW_SIZE, configuration.chatMemoryWindowSize)
        assertEquals(DEFAULT_KOOG_LONG_TERM_MEMORY_ENABLED, configuration.longTermMemoryEnabled)
        assertEquals(DEFAULT_KOOG_LONG_TERM_MEMORY_TOP_K, configuration.longTermMemoryTopK)
        assertEquals(DEFAULT_KOOG_OPEN_TELEMETRY_SERVICE_NAME, configuration.openTelemetryServiceName)
        assertEquals(DEFAULT_KOOG_OPEN_TELEMETRY_SERVICE_VERSION, configuration.openTelemetryServiceVersion)
        assertEquals(DEFAULT_KOOG_OPEN_TELEMETRY_VERBOSE, configuration.openTelemetryVerbose)
        assertNull(configuration.openTelemetryOtlpEndpoint)
    }

    @Test
    fun `koog feature configuration prefers environment over dotenv`() {
        val configuration =
            resolveKoogFeatureRuntimeConfiguration(
                environment =
                    mapOf(
                        "BERTBOT_KOOG_CHAT_MEMORY_ENABLED" to "false",
                        "BERTBOT_KOOG_CHAT_MEMORY_WINDOW_SIZE" to "120",
                        "BERTBOT_KOOG_LONG_TERM_MEMORY_ENABLED" to "false",
                        "BERTBOT_KOOG_LONG_TERM_MEMORY_TOP_K" to "9",
                        "BERTBOT_KOOG_OTEL_SERVICE_NAME" to "bertbot-koog",
                        "BERTBOT_KOOG_OTEL_SERVICE_VERSION" to "1.2.3",
                        "BERTBOT_KOOG_OTEL_VERBOSE" to "true",
                        "BERTBOT_KOOG_OTEL_OTLP_ENDPOINT" to "http://localhost:4317",
                    ),
                dotEnvValues =
                    mapOf(
                        "BERTBOT_KOOG_OTEL_SERVICE_NAME" to "ignored",
                    ),
            )

        assertEquals(false, configuration.chatMemoryEnabled)
        assertEquals(120, configuration.chatMemoryWindowSize)
        assertEquals(false, configuration.longTermMemoryEnabled)
        assertEquals(9, configuration.longTermMemoryTopK)
        assertEquals("bertbot-koog", configuration.openTelemetryServiceName)
        assertEquals("1.2.3", configuration.openTelemetryServiceVersion)
        assertEquals(true, configuration.openTelemetryVerbose)
        assertEquals("http://localhost:4317", configuration.openTelemetryOtlpEndpoint)
    }
}
