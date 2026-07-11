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
            setOf("coder", "planner", "architect", "analyst", "copywriter", "red_teamer", "philosopher", "psychologist"),
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

        assertTrue(copyMatches.contains("Copywriter"))
        assertTrue(redTeamMatches.contains("Red Teamer"))
        assertTrue(philosopherMatches.contains("Philosopher"))
        assertTrue(psychologistMatches.contains("Psychologist"))
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
