package com.personalagent.bertbot.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BertBotEnvironmentSettingsTest {
    // -------------------------------------------------------------------------
    // resolveRuntimeSetting – precedence
    // -------------------------------------------------------------------------

    @Test
    fun `resolveRuntimeSetting returns env value when both env and dotenv are present`() {
        val result =
            resolveRuntimeSetting(
                name = "KEY",
                environment = mapOf("KEY" to "env-value"),
                dotEnvValues = mapOf("KEY" to "dotenv-value"),
            )
        assertEquals("env-value", result)
    }

    @Test
    fun `resolveRuntimeSetting falls back to dotenv when env is absent`() {
        val result =
            resolveRuntimeSetting(
                name = "KEY",
                environment = emptyMap(),
                dotEnvValues = mapOf("KEY" to "dotenv-value"),
            )
        assertEquals("dotenv-value", result)
    }

    @Test
    fun `resolveRuntimeSetting returns null when key is absent from both sources`() {
        val result =
            resolveRuntimeSetting(
                name = "MISSING",
                environment = emptyMap(),
                dotEnvValues = emptyMap(),
            )
        assertNull(result)
    }

    @Test
    fun `resolveRuntimeSetting ignores blank env value and falls back to dotenv`() {
        val result =
            resolveRuntimeSetting(
                name = "KEY",
                environment = mapOf("KEY" to "   "),
                dotEnvValues = mapOf("KEY" to "dotenv-value"),
            )
        assertEquals("dotenv-value", result)
    }

    @Test
    fun `resolveRuntimeSetting returns null when env is blank and dotenv is absent`() {
        val result =
            resolveRuntimeSetting(
                name = "KEY",
                environment = mapOf("KEY" to ""),
                dotEnvValues = emptyMap(),
            )
        assertNull(result)
    }

    @Test
    fun `resolveRuntimeSetting strips surrounding double quotes from env value`() {
        val result =
            resolveRuntimeSetting(
                name = "KEY",
                environment = mapOf("KEY" to "\"quoted-value\""),
                dotEnvValues = emptyMap(),
            )
        assertEquals("quoted-value", result)
    }

    @Test
    fun `resolveRuntimeSetting strips surrounding double quotes from dotenv value`() {
        val result =
            resolveRuntimeSetting(
                name = "KEY",
                environment = emptyMap(),
                dotEnvValues = mapOf("KEY" to "\"dotenv-quoted\""),
            )
        assertEquals("dotenv-quoted", result)
    }

    @Test
    fun `resolveRuntimeSetting trims whitespace from env value`() {
        val result =
            resolveRuntimeSetting(
                name = "KEY",
                environment = mapOf("KEY" to "  trimmed  "),
                dotEnvValues = emptyMap(),
            )
        assertEquals("trimmed", result)
    }

    // -------------------------------------------------------------------------
    // resolveRuntimeSettingAllowBlank – blank-preserving semantics
    // -------------------------------------------------------------------------

    @Test
    fun `resolveRuntimeSettingAllowBlank returns blank string when env key is present with empty value`() {
        val result =
            resolveRuntimeSettingAllowBlank(
                name = "KEY",
                environment = mapOf("KEY" to ""),
                dotEnvValues = mapOf("KEY" to "dotenv-fallback"),
            )
        assertEquals("", result)
    }

    @Test
    fun `resolveRuntimeSettingAllowBlank returns null when key is absent from both sources`() {
        val result =
            resolveRuntimeSettingAllowBlank(
                name = "MISSING",
                environment = emptyMap(),
                dotEnvValues = emptyMap(),
            )
        assertNull(result)
    }

    @Test
    fun `resolveRuntimeSettingAllowBlank prefers env over dotenv`() {
        val result =
            resolveRuntimeSettingAllowBlank(
                name = "KEY",
                environment = mapOf("KEY" to "env-value"),
                dotEnvValues = mapOf("KEY" to "dotenv-value"),
            )
        assertEquals("env-value", result)
    }

    @Test
    fun `resolveRuntimeSettingAllowBlank falls back to dotenv when env key is absent`() {
        val result =
            resolveRuntimeSettingAllowBlank(
                name = "KEY",
                environment = emptyMap(),
                dotEnvValues = mapOf("KEY" to "dotenv-value"),
            )
        assertEquals("dotenv-value", result)
    }

    // -------------------------------------------------------------------------
    // toBooleanEnv – permissive boolean parsing
    // -------------------------------------------------------------------------

    @Test
    fun `toBooleanEnv accepts truthy tokens`() {
        for (truthy in listOf("1", "true", "yes", "on", "TRUE", "YES", "ON")) {
            assertEquals(true, truthy.toBooleanEnv(false), "Expected true for \"$truthy\"")
        }
    }

    @Test
    fun `toBooleanEnv accepts falsy tokens`() {
        for (falsy in listOf("0", "false", "no", "off", "FALSE", "NO", "OFF")) {
            assertEquals(false, falsy.toBooleanEnv(true), "Expected false for \"$falsy\"")
        }
    }

    @Test
    fun `toBooleanEnv returns default for unrecognised values`() {
        assertEquals(true, "maybe".toBooleanEnv(true))
        assertEquals(false, "maybe".toBooleanEnv(false))
        assertEquals(true, "".toBooleanEnv(true))
        assertEquals(false, "2".toBooleanEnv(false))
    }

    @Test
    fun `toBooleanEnv returns default for null receiver`() {
        val nullString: String? = null
        assertEquals(true, nullString.toBooleanEnv(true))
        assertEquals(false, nullString.toBooleanEnv(false))
    }

    // -------------------------------------------------------------------------
    // loadDotEnvValues – file parsing
    // -------------------------------------------------------------------------

    @Test
    fun `loadDotEnvValues parses key-value pairs and strips export prefix`() {
        val envFile = File.createTempFile("bertbot-test", ".env")
        envFile.deleteOnExit()
        envFile.writeText(
            """
            # comment line
            PLAIN_KEY=plain_value
            export EXPORTED_KEY=exported_value
            QUOTED_KEY="quoted value"
              PADDED_KEY = padded
            EMPTY_KEY=
            """.trimIndent(),
        )

        val values = loadDotEnvValues(envFile)
        assertEquals("plain_value", values["PLAIN_KEY"])
        assertEquals("exported_value", values["EXPORTED_KEY"])
        assertEquals("quoted value", values["QUOTED_KEY"])
        assertEquals("padded", values["PADDED_KEY"])
        assertEquals("", values["EMPTY_KEY"])
        assertNull(values["# comment line"])
    }

    @Test
    fun `loadDotEnvValues returns empty map when file does not exist`() {
        val nonExistentFile = File("/tmp/bertbot-nonexistent-${System.nanoTime()}.env")
        val values = loadDotEnvValues(nonExistentFile)
        assertEquals(emptyMap(), values)
    }

    // -------------------------------------------------------------------------
    // Integration – config resolvers use permissive boolean parsing end-to-end
    // -------------------------------------------------------------------------

    @Test
    fun `persistence configuration accepts permissive truthy boolean tokens`() {
        for (truthy in listOf("1", "yes", "on")) {
            val configuration =
                resolvePersistenceRuntimeConfiguration(
                    environment =
                        mapOf(
                            "BERTBOT_CHECKPOINT_AUTOSAVE_ENABLED" to truthy,
                            "BERTBOT_EVENT_SOURCING_ENABLED" to truthy,
                        ),
                    dotEnvValues = emptyMap(),
                )
            assertEquals(true, configuration.checkpointAutoSaveEnabled, "truthy token \"$truthy\" should set checkpointAutoSaveEnabled=true")
            assertEquals(true, configuration.eventSourcingEnabled, "truthy token \"$truthy\" should set eventSourcingEnabled=true")
        }
    }

    @Test
    fun `persistence configuration accepts permissive falsy boolean tokens`() {
        for (falsy in listOf("0", "no", "off")) {
            val configuration =
                resolvePersistenceRuntimeConfiguration(
                    environment =
                        mapOf(
                            "BERTBOT_CHECKPOINT_AUTOSAVE_ENABLED" to falsy,
                            "BERTBOT_EVENT_SOURCING_ENABLED" to falsy,
                        ),
                    dotEnvValues = emptyMap(),
                )
            assertEquals(false, configuration.checkpointAutoSaveEnabled, "falsy token \"$falsy\" should set checkpointAutoSaveEnabled=false")
            assertEquals(false, configuration.eventSourcingEnabled, "falsy token \"$falsy\" should set eventSourcingEnabled=false")
        }
    }

    @Test
    fun `koog feature configuration accepts permissive boolean tokens`() {
        val configuration =
            resolveKoogFeatureRuntimeConfiguration(
                environment =
                    mapOf(
                        "BERTBOT_KOOG_OTEL_VERBOSE" to "yes",
                    ),
                dotEnvValues = emptyMap(),
            )

        assertEquals(true, configuration.openTelemetryVerbose)
    }

    @Test
    fun `checkpoint rollback policy accepts permissive boolean tokens`() {
        val configuration =
            resolveCheckpointRollbackPolicyConfiguration(
                environment =
                    mapOf(
                        "BERTBOT_CHECKPOINT_ROLLBACK_ENABLED" to "on",
                        "BERTBOT_CHECKPOINT_ROLLBACK_REQUIRE_CONFIRM" to "off",
                        "BERTBOT_CHECKPOINT_ROLLBACK_ALLOW_PROTECTED" to "1",
                    ),
                dotEnvValues = emptyMap(),
            )

        assertEquals(true, configuration.rollbackEnabled)
        assertEquals(false, configuration.requireConfirm)
        assertEquals(true, configuration.allowInProtectedEnvironment)
    }
}
