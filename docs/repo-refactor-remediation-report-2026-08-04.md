# Repository Refactor Closure Report (2026-08-04)

## Scope

This report closes the repository-wide refactor and alignment pass focused on:

- persistence correctness and compatibility
- async lifecycle safety
- feature completeness validation
- docs/workflow alignment with runtime behavior

## Feature Matrix

| Surface | Implemented | Validation Evidence | Notes |
|---|---|---|---|
| Scoped state persistence (`file`, `jdbc`) | Yes | `FileBertBotStateStoreTest`, `JdbcBertBotStateStoreTest`, full `check` pass | Normalized key writes + legacy alias read compatibility |
| Episodic memory persistence (`file`, `jdbc`) | Yes | `MemoryArchitectureTest`, `JdbcBertBotMemoryStoreTest`, full `check` pass | Legacy alias fallback preserved |
| Profile persistence (`file`, `jdbc`) | Yes | `UserProfileStoreTest`, `JdbcUserProfileStoreTest`, full `check` pass | Legacy alias fallback preserved |
| Checkpoint persistence (`file`, `jdbc`) | Yes | `FileBertBotCheckpointStoreTest`, `JdbcBertBotCheckpointStoreTest` | Parse-failure warnings added |
| Event store persistence (`file`, `jdbc`) | Yes | `FileStateEventStoreTest`, `JdbcStateEventStoreTest` | Parse-failure warnings added |
| Async follow-up dispatch lifecycle | Yes | `ExternalChatPayloadDispatcherFollowupTest`, `ExternalChatAsyncRunnerTest` | Managed runner close/cancel path validated |
| MCP ingestion tools | Yes | Existing run-mode/docs alignment and startup tool lists | Listed in `docs/run-modes.md` |
| Checkpoint/rollback tools | Yes | Existing runtime + docs alignment | Listed in `docs/run-modes.md` |
| Cloud Run deploy automation | Yes | Workflow + script alignment updates, full `check` pass | Optional secret semantics normalized |
| Workflow pinning/guardrails | Yes | `enforce-pinned-actions.yml`, required contexts in bootstrap | Immutable action refs enforced |

## Completed Refactor Items

1. Deterministic, bounded scope-key normalization was introduced through `PersistenceScopeKey`.
2. Legacy compatibility was preserved via dual-read aliases (file: 200-char, JDBC: 255-char).
3. JDBC connection creation was centralized via `JdbcConnectionProvider` abstraction.
4. Async follow-up execution moved to lifecycle-managed `ManagedExternalChatAsyncRunner` with close support.
5. Persistence observability was improved for legacy fallback and unreadable payloads.
6. Workflow pinning and branch-protection required contexts were aligned.
7. Environment templates and deployment docs were aligned to runtime defaults.
8. Deploy/tag/merge workflows now include explicit timeout bounds where appropriate.

## Deferred Items

- No runtime behavior-changing JDBC pool implementation was enabled in this pass.
- No one-shot migration/backfill job was added to rewrite legacy scoped artifacts.

These remain intentionally deferred to keep this refactor behavior-preserving.

## Residual Risks

1. Legacy-scope fallback warnings may continue until old scoped rows/files are naturally rewritten or migrated.
2. Direct JDBC connection mode remains in use by default; high-throughput environments may later require pooled connections.
3. Workflow guardrails depend on branch-protection settings being applied and kept in sync.

## Verification Summary

- Full gate: `./gradlew.bat --no-daemon check` (pass).
- Targeted persistence and follow-up tests were executed during this refactor sequence.
- Docs and workflows were updated to match implemented runtime behavior.

## #117 Audit Addendum (Learning Parity Completion)

### High-Severity Findings

1. Missing first-class scheduler subsystem with durable execution history.
	- Resolved by adding scoped file/JDBC scheduled job + execution stores and scheduler control plane.
	- Evidence: `src/main/kotlin/com/personalagent/bertbot/app/ScheduledJobs.kt`, `src/main/kotlin/com/personalagent/bertbot/app/ScheduledJobToolRouter.kt`, `src/test/kotlin/com/personalagent/bertbot/app/ScheduledJobsTest.kt`.

2. Missing background learning-review proposal generation path.
	- Resolved by adding bounded, cooldown-protected proposal loop plus scope-isolated signal persistence.
	- Evidence: `src/main/kotlin/com/personalagent/bertbot/app/LearningProposalLoop.kt`, `src/test/kotlin/com/personalagent/bertbot/app/LearningProposalLoopTest.kt`.

3. Missing outcome-aware routing hint persistence and bounded influence logic.
	- Resolved by telemetry store + hint computation and delegation trace explainability integration.
	- Evidence: `src/main/kotlin/com/personalagent/bertbot/app/RoutingTelemetryStore.kt`, `src/main/kotlin/com/personalagent/bertbot/graph/nodes/DelegationNode.kt`, `src/test/kotlin/com/personalagent/bertbot/app/RoutingTelemetryStoreTest.kt`.

### Medium-Severity Findings

1. Config/documentation drift for new parity runtime surfaces.
	- Resolved by adding new configuration keys and MCP tool surface documentation.
	- Evidence: `docs/configuration.md`, `docs/run-modes.md`, `docs/learning-parity-status.md`.

2. Status/tool-surface visibility gaps for new optional routers.
	- Resolved by wiring scheduled jobs into MCP constants, startup tool lists, status provider, and dispatcher capability registry.
	- Evidence: `src/main/kotlin/com/personalagent/bertbot/app/McpConstants.kt`, `src/main/kotlin/com/personalagent/bertbot/app/McpStatusProviderFactory.kt`, `src/main/kotlin/com/personalagent/bertbot/app/McpServerMain.kt`, `src/main/kotlin/com/personalagent/bertbot/app/McpServerBootstrap.kt`.

### Residual Risks

1. Scheduler currently uses fixed-second cadence instead of cron expressions to keep compatibility risk low.
2. Background proposal loop starts with conservative heuristic keys and should be expanded incrementally with production feedback.
3. Multi-scope scheduler polling currently discovers default/global scope first; broader scope discovery can be added when tenant topology requirements are finalized.

### Final Verification Evidence

- Full gate pass: `./gradlew --no-daemon check`.
- New/updated tests include:
  - `RoutingTelemetryStoreTest`
  - `ScheduledJobsTest`
  - `LearningProposalLoopTest`
  - runtime/config/status fixture updates (`AiRuntimeConfigurationTest`, `McpStatusProviderFactoryTest`).
