package com.personalagent.bertbot.app

import com.openai.models.ChatModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        assertEquals(DEFAULT_STATE_FILE_PATH, configuration.stateFilePath)
        assertEquals(DEFAULT_EPISODIC_MEMORY_FILE_PATH, configuration.episodicMemoryFilePath)
        assertEquals(DEFAULT_SEMANTIC_MEMORY_FILE_PATH, configuration.semanticMemoryFilePath)
        assertEquals(DEFAULT_PROFILE_FILE_PATH, configuration.profileFilePath)
        assertEquals(DEFAULT_INGESTION_CONSENT_FILE_PATH, configuration.ingestionConsentFilePath)
        assertEquals(DEFAULT_INGESTION_SOURCE_STATE_FILE_PATH, configuration.ingestionSourceStateFilePath)
        assertEquals(DEFAULT_STATE_JDBC_TABLE, configuration.jdbcTable)
        assertEquals(DEFAULT_EPISODIC_MEMORY_JDBC_TABLE, configuration.episodicMemoryJdbcTable)
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
                        "BERTBOT_STATE_JDBC_URL" to "jdbc:postgresql://localhost:5432/bertbot",
                        "BERTBOT_STATE_JDBC_USER" to "env-user",
                        "BERTBOT_STATE_JDBC_PASSWORD" to "env-pass",
                        "BERTBOT_STATE_JDBC_TABLE" to "bertbot_state_table",
                        "BERTBOT_STATE_FILE_PATH" to "state-from-env.json",
                        "BERTBOT_MEMORY_EPISODIC_FILE_PATH" to "episodic-from-env.json",
                        "BERTBOT_MEMORY_SEMANTIC_FILE_PATH" to "semantic-from-env.json",
                        "BERTBOT_PROFILE_FILE_PATH" to "profile-from-env.json",
                        "BERTBOT_INGESTION_CONSENT_FILE_PATH" to "consent-from-env.json",
                        "BERTBOT_INGESTION_SOURCE_STATE_FILE_PATH" to "source-state-from-env.json",
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
        assertEquals("state-from-env.json", configuration.stateFilePath)
        assertEquals("episodic-from-env.json", configuration.episodicMemoryFilePath)
        assertEquals("semantic-from-env.json", configuration.semanticMemoryFilePath)
        assertEquals("profile-from-env.json", configuration.profileFilePath)
        assertEquals("consent-from-env.json", configuration.ingestionConsentFilePath)
        assertEquals("source-state-from-env.json", configuration.ingestionSourceStateFilePath)
        assertEquals("jdbc:postgresql://localhost:5432/bertbot", configuration.jdbcUrl)
        assertEquals("env-user", configuration.jdbcUser)
        assertEquals("env-pass", configuration.jdbcPassword)
        assertEquals("bertbot_state_table", configuration.jdbcTable)
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
}
