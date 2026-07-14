package com.personalagent.bertbot.app

import com.personalagent.bertbot.config.BertBotAgentConfig
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapabilityStatusResponseTest {
    @Test
    fun `capability response lists all sub-agents with enabled status`() {
        val response = buildCapabilityStatusResponse(BertBotAgentConfig(), "List all sub-agents and whether they are enabled or disabled")

        assertNotNull(response)
        assertTrue(response.contains("Sub-agents:"))
        assertTrue(response.contains("- polymarket_analyst: enabled"))
        assertTrue(response.contains("- philosopher: enabled"))
        assertTrue(response.contains("- psychologist: enabled"))
        assertTrue(response.contains("- google_workspace_operator: enabled"))
        assertTrue(response.contains("- repo_improvement_researcher: disabled"))
    }

    @Test
    fun `capability response includes Playwright and document access flags`() {
        val response = buildCapabilityStatusResponse(BertBotAgentConfig(), "Do you have access to my documents and can you use Playwright?")

        assertNotNull(response)
        assertTrue(response.contains("workspace.read_file (allowed file roots): enabled"))
        assertTrue(response.contains("Playwright capability: enabled"))
    }

    @Test
    fun `capability response marks google workspace configured but unavailable`() {
        val response =
            buildCapabilityStatusResponse(
                BertBotAgentConfig(),
                "What can you access in Google Workspace right now?",
                RuntimeCapabilitySnapshot(
                    googleWorkspaceConfigured = true,
                    googleWorkspaceToolAccessAvailable = false,
                ),
            )

        assertNotNull(response)
        assertTrue(response.contains("Google Workspace MCP: configured but unavailable"))
    }

    @Test
    fun `google workspace unavailable response intercepts calendar request`() {
        val response =
            buildGoogleWorkspaceUnavailableResponse(
                "What is upcoming in my calendar?",
                RuntimeCapabilitySnapshot(
                    googleWorkspaceConfigured = true,
                    googleWorkspaceToolAccessAvailable = false,
                ),
            )

        assertNotNull(response)
        assertTrue(response.contains("tool bridge is currently unavailable"))
        assertTrue(response.contains("check your calendar"))
    }

    @Test
    fun `capability response includes persistence store backend`() {
        val response =
            buildCapabilityStatusResponse(
                BertBotAgentConfig(),
                "What persistence backend are you using?",
                RuntimeCapabilitySnapshot(
                    persistenceBackend = "file",
                ),
            )

        assertNotNull(response)
        assertTrue(response.contains("Persistence store: file"))
    }

    @Test
    fun `capability response includes jdbc persistence backend when configured`() {
        val response =
            buildCapabilityStatusResponse(
                BertBotAgentConfig(),
                "What state store backend is configured?",
                RuntimeCapabilitySnapshot(
                    persistenceBackend = "postgresql",
                ),
            )

        assertNotNull(response)
        assertTrue(response.contains("Persistence store: postgresql"))
    }

    @Test
    fun `capability response includes playwright fallback status when available`() {
        val response =
            buildCapabilityStatusResponse(
                BertBotAgentConfig(),
                "Is Playwright fallback enabled?",
                RuntimeCapabilitySnapshot(
                    playwrightFallbackAvailable = true,
                ),
            )

        assertNotNull(response)
        assertTrue(response.contains("Playwright fallback: available"))
    }

    @Test
    fun `capability response indicates playwright fallback disabled when not configured`() {
        val response =
            buildCapabilityStatusResponse(
                BertBotAgentConfig(),
                "Is Playwright fallback enabled?",
                RuntimeCapabilitySnapshot(
                    playwrightFallbackAvailable = false,
                ),
            )

        assertNotNull(response)
        assertTrue(
            response.contains("Playwright fallback: agent-advertised") ||
                response.contains("Playwright fallback: disabled"),
        )
    }

    @Test
    fun `non-capability question returns null and does not include store info`() {
        val response = buildCapabilityStatusResponse(BertBotAgentConfig(), "What is the weather today?")

        assertNull(response)
    }
}
