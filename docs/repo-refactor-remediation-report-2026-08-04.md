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
