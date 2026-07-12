package com.personalagent.bertbot.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class McpStatusProviderFactoryTest {
    @Test
    fun `status includes checkpoint tool surface and rollback policy details`() {
        val statusProvider =
            McpStatusProviderFactory.create(
                McpStatusProviderInput(
                    startup = McpStartupState(runtime = null, errorMessage = "missing runtime"),
                    workspaceRoot = File("."),
                    aiRuntimeConfiguration = AiRuntimeConfiguration(provider = "openai", model = "gpt-4o-mini"),
                    macrofactorToolRouter = null,
                    googleWorkspaceToolRouter = null,
                    continuousResearchToolRouter = null,
                    toolNames = McpConstants.toolNames,
                    checkpointRollbackPolicy =
                        CheckpointRollbackPolicyConfiguration(
                            environment = "production",
                            rollbackEnabled = true,
                            requireConfirm = true,
                            allowInProtectedEnvironment = false,
                        ),
                ),
            )

        val status = statusProvider()

        assertTrue(status.contains("checkpoint_list"))
        assertTrue(status.contains("checkpoint_latest"))
        assertTrue(status.contains("checkpoint_get"))
        assertTrue(status.contains("checkpoint_rollback"))
        assertTrue(status.contains("checkpoint_rollback_latest"))
        assertTrue(status.contains("checkpoint_policy"))
        assertTrue(status.contains("Checkpoint rollback policy:"))
        assertTrue(status.contains("environment=production"))
        assertTrue(status.contains("protectedEnvironment=true"))
        assertTrue(status.contains("rollbackEnabled=true"))
        assertTrue(status.contains("requireConfirm=true"))
        assertTrue(status.contains("allowInProtectedEnvironment=false"))
    }
}
