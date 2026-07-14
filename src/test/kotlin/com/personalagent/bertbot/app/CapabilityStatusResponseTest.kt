package com.personalagent.bertbot.app

import com.personalagent.bertbot.config.BertBotAgentConfig
import kotlin.test.Test
import kotlin.test.assertNotNull
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
}
