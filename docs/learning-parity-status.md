# Learning Parity Status

Last updated: 2026-08-04

This document tracks practical parity progress for advanced learning-loop abilities in PersonalAgent/BertBot.

## Scope

- Product naming remains BertBot/PersonalAgent.
- Parity target is capability-level parity for the closed learning loop and scheduler surfaces.
- This tracker is implementation-oriented and evidence-backed.

## Status Overview

### Implemented

1. Durable session history persistence (file + JDBC scoped backends).
2. Session history MCP controls for list and clear.
3. Session history search capability (store, runtime, MCP routing and tool definition).
4. Learning-review approval queue persistence (file + JDBC) for memory/skill write gating.
5. Learning-review MCP controls for list/approve/reject.
6. Learning-review approval reliability hardening:
   - approval finalization only when apply succeeds,
   - persistent failure metadata on pending requests,
   - surfaced failure metadata in list output,
   - explicit apply-failure messages in approve responses.

### In Progress

1. Consolidated parity reporting and phase acceptance criteria (this document is the implementation baseline).

### Not Started

1. No remaining phase-1 parity gaps in the scoped backlog tracked by #110/#111/#112/#113/#114/#115.

## Evidence Map

### Session History + Search

- Runtime APIs: `listSessionHistory`, `searchSessionHistory`, `clearSessionHistory` in `src/main/kotlin/com/personalagent/bertbot/app/BertBotRuntime.kt`.
- Persistence implementations: `FileSessionHistoryStore`, `JdbcSessionHistoryStore` in `src/main/kotlin/com/personalagent/bertbot/app/SessionHistoryStore.kt`.
- MCP surface: `session_history_list`, `session_history_search`, `session_history_clear` in `src/main/kotlin/com/personalagent/bertbot/app/SessionHistoryToolRouter.kt`.

### Learning Review

- Queue store and failure metadata persistence: `src/main/kotlin/com/personalagent/bertbot/app/LearningReviewStore.kt`.
- Runtime approval/reject outcomes and apply-failure recording: `src/main/kotlin/com/personalagent/bertbot/app/BertBotRuntime.kt`.
- MCP surface and failure-metadata rendering: `src/main/kotlin/com/personalagent/bertbot/app/LearningReviewToolRouter.kt`.

### Curated Memory

- Store implementations and bounded eviction: `src/main/kotlin/com/personalagent/bertbot/app/CuratedMemoryStore.kt`.
- Runtime wiring and persistence configuration: `src/main/kotlin/com/personalagent/bertbot/app/BertBotRuntime.kt`, `src/main/kotlin/com/personalagent/bertbot/app/BertBotSupport.kt`.

### Procedural Skill Lifecycle

- Skill artifact persistence (file/JDBC) and staged approval lifecycle: `src/main/kotlin/com/personalagent/bertbot/app/ProceduralSkillStore.kt`.
- MCP control plane: `src/main/kotlin/com/personalagent/bertbot/app/ProceduralSkillToolRouter.kt`.

### Routing Hints

- Telemetry store and bounded hint computation: `src/main/kotlin/com/personalagent/bertbot/app/RoutingTelemetryStore.kt`.
- Delegation integration with trace explainability: `src/main/kotlin/com/personalagent/bertbot/graph/nodes/DelegationNode.kt`.

### Scheduled Jobs

- Job + execution persistence and scheduler service: `src/main/kotlin/com/personalagent/bertbot/app/ScheduledJobs.kt`.
- MCP control plane: `src/main/kotlin/com/personalagent/bertbot/app/ScheduledJobToolRouter.kt`.

### Background Learning Proposal Loop

- Signal persistence and bounded/cooldown proposal loop: `src/main/kotlin/com/personalagent/bertbot/app/LearningProposalLoop.kt`.
- Runtime signal capture on conversation outcomes: `src/main/kotlin/com/personalagent/bertbot/app/BertBotRuntime.kt`.

### Focused Verification Tests

- `src/test/kotlin/com/personalagent/bertbot/app/SessionHistoryStoreTest.kt`
- `src/test/kotlin/com/personalagent/bertbot/app/SessionHistoryToolRouterTest.kt`
- `src/test/kotlin/com/personalagent/bertbot/app/McpRequestDispatcherSessionHistoryTest.kt`
- `src/test/kotlin/com/personalagent/bertbot/app/LearningReviewStoreTest.kt`
- `src/test/kotlin/com/personalagent/bertbot/app/LearningReviewToolRouterTest.kt`
- `src/test/kotlin/com/personalagent/bertbot/app/McpRequestDispatcherLearningReviewTest.kt`
- `src/test/kotlin/com/personalagent/bertbot/app/BertBotRuntimeHelpersTest.kt`
- `src/test/kotlin/com/personalagent/bertbot/app/CuratedMemoryStoreTest.kt`
- `src/test/kotlin/com/personalagent/bertbot/app/ProceduralSkillStoreTest.kt`
- `src/test/kotlin/com/personalagent/bertbot/app/ProceduralSkillToolRouterTest.kt`
- `src/test/kotlin/com/personalagent/bertbot/app/RoutingTelemetryStoreTest.kt`
- `src/test/kotlin/com/personalagent/bertbot/app/ScheduledJobsTest.kt`
- `src/test/kotlin/com/personalagent/bertbot/app/LearningProposalLoopTest.kt`

## Phase Gates

### Phase 1 Gate (Session Foundation)

- Durable session persistence: complete.
- Session listing/clearing MCP tools: complete.
- Session search MCP tool: complete.
- Prompt-time retrieved excerpt integration: pending.

### Phase 2 Gate (Learning Review)

- Approval queue persistence: complete.
- MCP review controls: complete.
- Apply-failure durability + operator visibility: complete.
- Background learning-review proposal loop: complete.

### Phase 3 Gate (Procedural Skills)

- Skill lifecycle persistence + approval: complete.

### Phase 4 Gate (Scheduler)

- First-class scheduled jobs subsystem: complete.

## Next Acceptance Targets

1. Expand cross-scope scheduler discovery beyond default scope for multi-tenant deployments.
2. Add optional richer schedule expressions after validating backward compatibility with fixed-interval jobs.
3. Add additional heuristics for proposal signals while preserving current dedupe/cooldown protections.