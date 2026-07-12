package com.personalagent.bertbot.agents

import com.personalagent.bertbot.config.BertBotAgentConfig
import com.personalagent.bertbot.config.SubAgentConfigDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SubAgentRegistryTest {
    @Test
    fun `registry exposes orchestrator default agents`() {
        val registry = SubAgentRegistry()
        val ids = registry.enabledAgents().map { it.id }.toSet()

        assertEquals(
            setOf(
                "coder",
                "planner",
                "architect",
                "analyst",
                "polymarket_analyst",
                "copywriter",
                "red_teamer",
                "philosopher",
                "psychologist",
                "google_workspace_operator",
            ),
            ids,
        )
    }

    @Test
    fun `registry finds architect for architecture-heavy task`() {
        val registry = SubAgentRegistry()
        val match = registry.findBestMatch("Review system architecture and dependency structure")

        assertNotNull(match)
        assertEquals("architect", match.id)
    }

    @Test
    fun `registry can identify copywriter and red team roles`() {
        val registry = SubAgentRegistry()

        val copyMatches = registry.describeMatches("Please rewrite this message and improve tone")
        val redTeamMatches = registry.describeMatches("Stress test for edge risk and security failures")
        val philosopherMatches = registry.describeMatches("Reflect on meaning and values from first principles")
        val psychologistMatches = registry.describeMatches("Help with behavior patterns and emotional communication")

        assertTrue(copyMatches.contains("Copywriter Agent"))
        assertTrue(redTeamMatches.contains("Red Team Agent"))
        assertTrue(philosopherMatches.contains("Philosopher Agent"))
        assertTrue(psychologistMatches.contains("Psychologist Agent"))
    }

    @Test
    fun `registry routes polymarket market analysis requests to polymarket analyst`() {
        val registry = SubAgentRegistry()

        val match = registry.findBestMatch("Analyze Polymarket odds, open interest, and order book liquidity")

        assertNotNull(match)
        assertEquals("polymarket_analyst", match.id)
    }

    @Test
    fun `registry routes playwright browser automation requests to coder`() {
        val registry = SubAgentRegistry()

        val match = registry.findBestMatch("Use playwright mcp for end-to-end browser automation checks")

        assertNotNull(match)
        assertEquals("coder", match.id)
    }

    @Test
    fun `registry routes google workspace requests to google workspace operator`() {
        val registry = SubAgentRegistry()

        val match = registry.findBestMatch("Use Google Workspace MCP to draft a Gmail update and schedule a calendar event")

        assertNotNull(match)
        assertEquals("google_workspace_operator", match.id)
    }

    @Test
    fun `registry routes github mcp remote server toolset requests to coder`() {
        val registry = SubAgentRegistry()

        val match = registry.findBestMatch("Configure GitHub MCP remote server toolsets for repos, issues, pull_requests, and actions")

        assertNotNull(match)
        assertEquals("coder", match.id)
    }

    @Test
    fun `registry derives enabled agents from agent config`() {
        val config =
            BertBotAgentConfig(
                subAgents =
                    listOf(
                        SubAgentConfigDefinition(
                            id = "qa",
                            name = "QA",
                            description = "Finds regressions",
                            skills = setOf("test", "qa", "regression"),
                        ),
                        SubAgentConfigDefinition(
                            id = "disabled_agent",
                            name = "Disabled",
                            description = "Should not be active",
                            skills = setOf("disabled"),
                            enabled = false,
                        ),
                    ),
            )

        val registry = SubAgentRegistry(config)
        val ids = registry.enabledAgents().map { it.id }

        assertEquals(listOf("qa"), ids)
        assertEquals("QA", registry.findBestMatch("Run qa regression testing")?.name)
    }
}
