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
7. Environment templates (`.env.example`, `.env.compose.example`) and `docs/configuration.md` updated
   to document all learning-review env keys (`BERTBOT_LEARNING_REVIEW_FILE_PATH`,
   `BERTBOT_LEARNING_REVIEW_JDBC_TABLE`, `BERTBOT_LEARNING_REVIEW_DECISION_JDBC_TABLE`).

### In Progress

1. Session retrieval integration into prompt assembly as on-demand recall excerpts (search exists; prompt path still pending).
2. Consolidated parity reporting and phase acceptance criteria (this document is the initial baseline).

### Not Started

1. Bounded curated memory store distinct from episodic/semantic/profile layers.
2. Procedural skill lifecycle store (create/patch/archive/supersede) with approval staging.
3. Outcome-aware delegation/tool-routing hints from historical success/failure telemetry.
4. First-class scheduled jobs subsystem (create/list/update/pause/resume/run/remove + execution history + chained context).

## Evidence Map

### Session History + Search

- Runtime APIs: `listSessionHistory`, `searchSessionHistory`, `clearSessionHistory` in `src/main/kotlin/com/personalagent/bertbot/app/BertBotRuntime.kt`.
- Persistence implementations: `FileSessionHistoryStore`, `JdbcSessionHistoryStore` in `src/main/kotlin/com/personalagent/bertbot/app/SessionHistoryStore.kt`.
- MCP surface: `session_history_list`, `session_history_search`, `session_history_clear` in `src/main/kotlin/com/personalagent/bertbot/app/SessionHistoryToolRouter.kt`.

### Learning Review

- Queue store and failure metadata persistence: `src/main/kotlin/com/personalagent/bertbot/app/LearningReviewStore.kt`.
- Runtime approval/reject outcomes and apply-failure recording: `src/main/kotlin/com/personalagent/bertbot/app/BertBotRuntime.kt`.
- MCP surface and failure-metadata rendering: `src/main/kotlin/com/personalagent/bertbot/app/LearningReviewToolRouter.kt`.

### Focused Verification Tests

- `src/test/kotlin/com/personalagent/bertbot/app/SessionHistoryStoreTest.kt`
- `src/test/kotlin/com/personalagent/bertbot/app/SessionHistoryToolRouterTest.kt`
- `src/test/kotlin/com/personalagent/bertbot/app/McpRequestDispatcherSessionHistoryTest.kt`
- `src/test/kotlin/com/personalagent/bertbot/app/LearningReviewStoreTest.kt`
- `src/test/kotlin/com/personalagent/bertbot/app/LearningReviewToolRouterTest.kt`
- `src/test/kotlin/com/personalagent/bertbot/app/McpRequestDispatcherLearningReviewTest.kt`
- `src/test/kotlin/com/personalagent/bertbot/app/BertBotRuntimeHelpersTest.kt`

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
- Background learning-review proposal loop: pending.

### Phase 3 Gate (Procedural Skills)

- Skill lifecycle persistence + approval: not started.

### Phase 4 Gate (Scheduler)

- First-class scheduled jobs subsystem: not started.

## Next Acceptance Targets

1. Add retrieved session-excerpt injection path that is disabled by default and explicitly enabled by request/tool context.
2. Introduce curated memory store boundaries and limits with compatibility-safe migration behavior.
3. Define procedural skill artifact format and approval lifecycle with tests.
4. Stand up scheduled jobs data model and control-plane tools.