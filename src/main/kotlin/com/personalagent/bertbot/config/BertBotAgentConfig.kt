package com.personalagent.bertbot.config

data class ToolDefinition(
    val name: String,
    val description: String,
    val enabled: Boolean = true,
)

data class SkillDefinition(
    val name: String,
    val description: String,
    val enabled: Boolean = true,
)

data class SubAgentConfigDefinition(
    val id: String,
    val name: String,
    val description: String,
    val skills: Set<String>,
    val enabled: Boolean = true,
)

data class BertBotAgentConfig(
    val name: String = "BertBot",
    val maxSemanticContextEntries: Int = 5,
    val maxEpisodicContextEntries: Int = 10,
    val memorySummarizationThreshold: Int = 15,
    val memorySummarizationBatchSize: Int = 10,
    val systemPrompt: String =
        """
        You are BertBot, my premium personal assistant and chief orchestration agent.
        You are the primary interface I interact with. Rather than solving everything yourself, your primary mandate is to act as a master strategist—mapping complex user goals into dependency graphs and delegating work to your fleet of specialized sub-agents.

        ## 1. CORE RESPONSIBILITIES
        1. Reading my messages and replying in a tone that naturally matches mine.
        2. Learning my preferences over time and refining your tone, architecture choices, and responses.
        3. Remembering key context: my routines, my long-term project architectures, and working preferences.
        4. Identifying tasks that require my explicit attention and separating high-signal alerts from noise.
        5. Deepening personal context over time via tools, minimizing repetitive clarification questions.
        6. Delegating work to specialized sub-agents when their skills match the task, acting as their manager.

        ## 2. THE CHIEF ORCHESTRATOR WORKFLOW
        When a request arrives, do not immediately execute code or research yourself. Follow this pipeline:
        *   **Decompose:** Break down complex goals into logical, multi-step sub-tasks.
        *   **Route:** Assign sub-tasks to the correct specialized sub-agents (e.g., Coder, Planner, Architect, Analyst, Copywriter, Red Teamer).
        *   **Verify (The Critic Loop):** Never pass raw sub-agent outputs directly to me. Route code through the Red Teamer for edge cases, and route plans through the Architect for structural soundness. Reject substandard work internally.

        ## 3. COMMUNICATION MODES & PERSONA
        Be warm, thoughtful, practical, and technically sharp. Match your response format to the situation:
        *   **Mode 1: The Blueprint (For complex plans):** Before kicking off a long sub-agent execution chain, present a brief, scannable summary mapping out [Goal], [Workflow Steps], and [User Approvals Needed].
        *   **Mode 2: The Direct Deliverable:** For standard requests, present the finalized, clean product. Hide the internal agent-to-agent debates.
        *   **Mode 3: The Async Update:** When sub-agents are running long background tasks, provide low-noise updates: [Status], [Action Taken], [Next Milestone].

        If a task clearly fits a specialized sub-agent, use your agent tools to delegate it. If you are deeply uncertain about the underlying intent, ask a crisp, clarifying question rather than executing blindly.
        """.trimIndent(),
    val tools: List<ToolDefinition> =
        listOf(
            ToolDefinition(
                name = "memory.read",
                description = "Read remembered personal context, project histories, and user preferences",
            ),
            ToolDefinition(
                name = "memory.write",
                description = "Store important personal context, learned preferences, and newly discovered constraints",
            ),
            ToolDefinition(
                name = "workspace.search",
                description = "Search files and content in the current project workspace",
            ),
            ToolDefinition(
                name = "workspace.read_file",
                description = "Read source code and markdown configuration files from the workspace",
            ),
            ToolDefinition(
                name = "terminal.run",
                description = "Execute shell commands, build tasks, or gradle targets in the workspace",
            ),
            ToolDefinition(
                name = "agent.delegate",
                description = "Call a specialized sub-agent (e.g., 'coder', 'planner', 'architect', 'analyst', 'copywriter', 'red_teamer', 'philosopher', 'psychologist') with a specific context and task payload",
            ),
            ToolDefinition(
                name = "agent.broadcast",
                description = "Concurrently trigger multiple sub-agents to parallelize independent tasks (e.g., running coder and red_teamer concurrently)",
            ),
        ),
    val skills: List<SkillDefinition> =
        listOf(
            SkillDefinition(
                name = "personalization",
                description = "Dynamically analyze, map, and adapt to my tone, communication style, and lifestyle context",
            ),
            SkillDefinition(
                name = "orchestration",
                description = "Break complex objectives into dependency graphs, schedule concurrent agent tasks, and manage sub-agent lifetimes",
            ),
            SkillDefinition(
                name = "prioritization",
                description = "Filter incoming tasks to isolate urgent matters from backlog items, shielding me from system noise",
            ),
            SkillDefinition(
                name = "memory_management",
                description = "Maintain structural graphs of past interactions, preferences, and environment configurations",
            ),
            SkillDefinition(
                name = "kotlin_ecosystem",
                description = "Deep expertise working with Kotlin source files, graph orchestration, and Gradle JVM projects",
            ),
            SkillDefinition(
                name = "software_architecture",
                description = "Assess system design patterns, component relationships, and data flows to guide the Architect sub-agent",
            ),
            SkillDefinition(
                name = "adversarial_debugging",
                description = "Investigate system runtime errors, find complex state-machine deadlocks, and hunt down hidden edge cases via the Red Teamer sub-agent",
            ),
        ),
    val subAgents: List<SubAgentConfigDefinition> =
        listOf(
            SubAgentConfigDefinition(
                id = "coder",
                name = "Coder",
                description = "Handles implementation, debugging, and refactoring tasks",
                skills = setOf("implementation", "coding", "kotlin", "debugging", "refactoring"),
            ),
            SubAgentConfigDefinition(
                id = "planner",
                name = "Planner",
                description = "Handles prioritization, scheduling, and task organization",
                skills = setOf("planning", "prioritization", "scheduling", "organization", "workflow"),
            ),
            SubAgentConfigDefinition(
                id = "architect",
                name = "Architect",
                description = "Reviews plans and system designs for structural soundness",
                skills = setOf("architecture", "design", "system", "structure", "dependencies"),
            ),
            SubAgentConfigDefinition(
                id = "analyst",
                name = "Analyst",
                description = "Performs analysis, triage, and evidence-driven recommendations",
                skills = setOf("analysis", "triage", "metrics", "evaluation", "summary"),
            ),
            SubAgentConfigDefinition(
                id = "copywriter",
                name = "Copywriter",
                description = "Produces polished user-facing messaging and rewritten drafts",
                skills = setOf("copy", "writing", "rewrite", "message", "tone"),
            ),
            SubAgentConfigDefinition(
                id = "red_teamer",
                name = "Red Teamer",
                description = "Stress-tests outputs, hunts edge cases, and surfaces hidden risks",
                skills = setOf("adversarial", "edge", "risk", "failure", "security"),
            ),
            SubAgentConfigDefinition(
                id = "philosopher",
                name = "Philosopher",
                description = "Explores meaning, ethics, values, and first-principles reasoning",
                skills = setOf("philosophy", "ethics", "meaning", "values", "first principles"),
            ),
            SubAgentConfigDefinition(
                id = "psychologist",
                name = "Psychologist",
                description = "Supports behavior insight, emotional framing, and communication dynamics",
                skills = setOf("psychology", "behavior", "emotion", "mindset", "communication"),
            ),
        ),
) {
    init {
        require(maxSemanticContextEntries > 0) {
            "maxSemanticContextEntries must be greater than 0"
        }
        require(maxSemanticContextEntries <= MAX_SEMANTIC_CONTEXT_ENTRIES) {
            "maxSemanticContextEntries must be less than or equal to $MAX_SEMANTIC_CONTEXT_ENTRIES"
        }
        require(maxEpisodicContextEntries > 0) {
            "maxEpisodicContextEntries must be greater than 0"
        }
        require(maxEpisodicContextEntries <= MAX_EPISODIC_CONTEXT_ENTRIES) {
            "maxEpisodicContextEntries must be less than or equal to $MAX_EPISODIC_CONTEXT_ENTRIES"
        }
        require(memorySummarizationThreshold > 0) {
            "memorySummarizationThreshold must be greater than 0"
        }
        require(memorySummarizationThreshold <= MAX_MEMORY_SUMMARIZATION_THRESHOLD) {
            "memorySummarizationThreshold must be less than or equal to $MAX_MEMORY_SUMMARIZATION_THRESHOLD"
        }
        require(memorySummarizationBatchSize > 0) {
            "memorySummarizationBatchSize must be greater than 0"
        }
        require(memorySummarizationBatchSize <= MAX_MEMORY_SUMMARIZATION_BATCH_SIZE) {
            "memorySummarizationBatchSize must be less than or equal to $MAX_MEMORY_SUMMARIZATION_BATCH_SIZE"
        }
        require(memorySummarizationBatchSize <= memorySummarizationThreshold) {
            "memorySummarizationBatchSize must be less than or equal to memorySummarizationThreshold"
        }
    }

    fun enabledTools(): List<ToolDefinition> = tools.filter { it.enabled }

    fun enabledSkills(): List<SkillDefinition> = skills.filter { it.enabled }

    fun enabledSubAgents(): List<SubAgentConfigDefinition> = subAgents.filter { it.enabled }

    companion object {
        const val MAX_SEMANTIC_CONTEXT_ENTRIES: Int = 100
        const val MAX_EPISODIC_CONTEXT_ENTRIES: Int = 500
        const val MAX_MEMORY_SUMMARIZATION_THRESHOLD: Int = 1_000
        const val MAX_MEMORY_SUMMARIZATION_BATCH_SIZE: Int = 500
    }
}
