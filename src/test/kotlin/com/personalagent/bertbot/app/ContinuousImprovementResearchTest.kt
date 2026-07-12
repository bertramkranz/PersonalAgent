package com.personalagent.bertbot.app

import com.google.gson.JsonObject
import com.personalagent.bertbot.config.BertBotAgentConfig
import com.personalagent.bertbot.config.ContinuousImprovementResearchConfig
import com.personalagent.bertbot.llm.LlmGateway
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContinuousImprovementResearchTest {
    @Test
    fun `manual run writes recommendations to file store`() {
        val workspace = createTempDirectory(prefix = "research-workspace").toFile()
        workspace.deleteOnExit()
        File(workspace, "src/main/kotlin/com/personalagent/bertbot").mkdirs()

        val storageFile = File(workspace, "recommendations.json")
        val service =
            ContinuousImprovementResearchService(
                config = BertBotAgentConfig(),
                workspaceRoot = workspace,
                store = FileImprovementRecommendationStore(storageFile),
            )

        val report = service.runNow("test_manual")
        val recommendations = service.listRecommendations(limit = 10)

        assertTrue(report.executed)
        assertTrue(report.recommendationCount > 0)
        assertTrue(storageFile.exists())
        assertTrue(recommendations.isNotEmpty())
    }

    @Test
    fun `event run obeys minimum interval throttle`() {
        val workspace = createTempDirectory(prefix = "research-throttle").toFile()
        workspace.deleteOnExit()

        var currentMillis = 1_000_000L
        val service =
            ContinuousImprovementResearchService(
                config =
                    BertBotAgentConfig(
                        research =
                            ContinuousImprovementResearchConfig(
                                minIntervalBetweenRunsSeconds = 60,
                            ),
                    ),
                workspaceRoot = workspace,
                store = FileImprovementRecommendationStore(File(workspace, "recommendations.json")),
                nowMillis = { currentMillis },
            )

        val first = service.maybeRunEvent("first")
        val second = service.maybeRunEvent("second")

        assertTrue(first.executed)
        assertEquals(false, second.executed)
        assertEquals("min_interval_not_elapsed", second.skippedReason)

        currentMillis += 61_000L
        val third = service.maybeRunEvent("third")
        assertTrue(third.executed)
    }

    @Test
    fun `router lists and triggers recommendations`() {
        val workspace = createTempDirectory(prefix = "research-router").toFile()
        workspace.deleteOnExit()

        val service =
            ContinuousImprovementResearchService(
                config = BertBotAgentConfig(),
                workspaceRoot = workspace,
                store = FileImprovementRecommendationStore(File(workspace, "recommendations.json")),
            )
        service.runNow("bootstrap")

        val router = ContinuousResearchToolRouter(service)
        val listResult = router.handle(RESEARCH_LIST_TOOL_NAME, JsonObject())
        assertEquals(false, listResult?.first)
        assertTrue(listResult?.second?.contains("title=") == true)

        val runParams = JsonObject()
        runParams.addProperty("reason", "router")
        val callParams = JsonObject()
        callParams.add("arguments", runParams)
        val runResult = router.handle(RESEARCH_RUN_NOW_TOOL_NAME, callParams)
        assertEquals(false, runResult?.first)
        assertTrue(runResult?.second?.contains("executed=true") == true)
    }

    @Test
    fun `llm assisted mode merges parsed recommendations`() {
        val workspace = createTempDirectory(prefix = "research-llm").toFile()
        workspace.deleteOnExit()
        File(workspace, "README.md").writeText("sample")

        val gateway =
            object : LlmGateway {
                override fun complete(
                    systemPrompt: String,
                    userPrompt: String,
                ): String =
                    """
                    {
                      "recommendations": [
                        {
                          "key": "ai.llm.plan",
                          "title": "Add plan quality rubric",
                          "category": "ai",
                          "rationale": "A rubric improves recommendation consistency.",
                          "evidence": ["README.md"],
                          "impact": 4,
                          "effort": 2,
                          "confidence": 4
                        }
                      ]
                    }
                    """.trimIndent()
            }

        val service =
            ContinuousImprovementResearchService(
                config =
                    BertBotAgentConfig(
                        research =
                            ContinuousImprovementResearchConfig(
                                llmAssistedEnabled = true,
                            ),
                    ),
                workspaceRoot = workspace,
                store = FileImprovementRecommendationStore(File(workspace, "recommendations.json")),
                llmGateway = gateway,
            )

        service.runNow("llm")
        val recommendations = service.listRecommendations(limit = 20)
        assertTrue(recommendations.any { recommendation -> recommendation.key == "ai.llm.plan" })
    }

    @Test
    fun `submitEventAsync dispatches research cycle without blocking`() {
        val workspace = createTempDirectory(prefix = "research-async").toFile()
        workspace.deleteOnExit()

        val service =
            ContinuousImprovementResearchService(
                config = BertBotAgentConfig(),
                workspaceRoot = workspace,
                store = FileImprovementRecommendationStore(File(workspace, "recommendations.json")),
            )

        service.use {
            it.submitEventAsync("async_test")
            Thread.sleep(2_000)
            assertTrue(it.listRecommendations(limit = 1).isNotEmpty())
        }
    }
}
