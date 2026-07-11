package com.personalagent.bertbot.agents

import com.personalagent.bertbot.config.BertBotAgentConfig

data class SubAgentDefinition(
    val id: String,
    val name: String,
    val description: String,
    val skills: Set<String>,
    val enabled: Boolean = true,
)

class SubAgentRegistry(
    config: BertBotAgentConfig = BertBotAgentConfig(),
    private val definitions: List<SubAgentDefinition> =
        config.enabledSubAgents().map { definition ->
            SubAgentDefinition(
                id = definition.id,
                name = definition.name,
                description = definition.description,
                skills = definition.skills,
                enabled = definition.enabled,
            )
        },
) {
    fun enabledAgents(): List<SubAgentDefinition> = definitions.filter { it.enabled }

    fun findBestMatch(task: String): SubAgentDefinition? {
        val normalizedTask = task.lowercase()
        return enabledAgents()
            .filter { agent ->
                agent.skills.any { skill ->
                    normalizedTask.contains(skill.lowercase())
                }
            }
            .maxByOrNull { agent ->
                agent.skills.count { skill -> normalizedTask.contains(skill.lowercase()) }
            }
    }

    fun describeMatches(task: String): List<String> =
        enabledAgents().filter { agent ->
            agent.skills.any { skill -> task.lowercase().contains(skill.lowercase()) }
        }.map { it.name }
}
