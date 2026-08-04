# Configuration

BertBot reads configuration from process environment variables first and then falls back to `.env` in the repository root.

## Environment File Usage

Each environment file has a single owner and runtime context. Do not mix these files across contexts.

| File | Purpose | When to use |
| --- | --- | --- |
| `.env.example` | Local development template | One-time: copy to `.env` before the first local Gradle run |
| `.env` | Local runtime configuration | Loaded automatically by all Gradle run modes |
| `.env.compose.example` | Docker Compose template | One-time: copy to `.env.compose` before the first compose run |
| `.env.compose` | Compose runtime configuration | Loaded by `docker-compose.yml` via `env_file` |
| Secret Manager / platform env | Deployed runtime credentials | Injected as environment variables by Cloud Run at deploy time; no file used |

**Ownership rules:**

- Local Gradle runs (CLI, headless, MCP, webhook, Discord): use `.env` only.
- Docker Compose runs: use `.env.compose` only.
- Cloud Run and other hosted deployments: do not use a file; inject all variables through the platform secret or environment mechanism.

Neither `.env` nor `.env.compose` should ever be committed. Both are excluded by `.gitignore`.

Use [../scripts/check-env-drift.sh](../scripts/check-env-drift.sh) after changing either template to verify active-key parity.

To bootstrap local development, copy [../.env.example](../.env.example) to `.env` and set your provider-specific values.

Template defaults in [../.env.example](../.env.example) are intentionally conservative: most optional integrations default to disabled; Telegram is the exception and defaults to enabled so webhook replies work immediately once a bot token is set.

For Docker Compose deployment, copy [../.env.compose.example](../.env.compose.example) to `.env.compose`. Compose-specific overrides in that file are annotated with `[compose override]`; all other keys share the same semantics as in `.env.example`.

See [run-modes.md](run-modes.md) for runtime-specific commands, [deployment.md](deployment.md) for Docker Compose and Cloud Run guidance, and [vscode-copilot.md](vscode-copilot.md) for workspace MCP setup.  For Koog beta dependency pinning and upgrade rules, see [koog-beta-upgrade-policy.md](koog-beta-upgrade-policy.md).

## Environment Variable Parsing

All settings follow the same precedence order:

1. **Process environment** – highest priority.
2. **`.env` file** in the repository root – fallback when the process environment value is blank or absent.
3. **Compiled default** – applied when neither source provides a value.

Boolean settings accept both the strict tokens (`true` / `false`) and the common shell idioms
`1` / `0`, `yes` / `no`, and `on` / `off` (case-insensitive).  Any unrecognised value falls back to
the compiled default.

## Environment Key Matrix

The table below lists every recognized env key, its code default, and the value set in each example template. Keys absent from a template use the code default. `[required]` means the variable has no usable default and must be set.

| Key | Code default | `.env.example` | `.env.compose.example` | Notes |
| --- | --- | --- | --- | --- |
| `BERTBOT_AI_PROVIDER` | `openai` | `openai` | `openai` | `openai` or `ollama` |
| `BERTBOT_AI_MODEL` | `gpt-4o-mini` | `gpt-4o-mini` | `gpt-4o-mini` | |
| `BERTBOT_AI_API_KEY` | — | `[required]` | `[required]` | Required for OpenAI |
| `BERTBOT_OLLAMA_BASE_URL` | `http://localhost:11434` | `http://localhost:11434` | `http://ollama:11434` | [compose override] Docker service hostname |
| `BERTBOT_OLLAMA_TIMEOUT_SECONDS` | `120` | `120` | `120` | |
| `BERTBOT_WORKSPACE_ROOT` | `.` | `.` | `.` | |
| `BERTBOT_MACROFACTOR_ENABLED` | `false` | `false` | `false` | |
| `BERTBOT_MACROFACTOR_COMMAND` | `npx` | `npx` | `npx` | |
| `BERTBOT_MACROFACTOR_ARGS` | `-y,sjawhar-macrofactor` | `-y,sjawhar-macrofactor` | `-y,sjawhar-macrofactor` | |
| `BERTBOT_MACROFACTOR_USERNAME` | — | `` | `` | |
| `BERTBOT_MACROFACTOR_PASSWORD` | — | `` | `` | |
| `BERTBOT_MACROFACTOR_TIMEOUT_SECONDS` | `45` | `45` | `45` | |
| `BERTBOT_MACROFACTOR_TOOL_NAME_PREFIX` | `macrofactor_` | `macrofactor_` | `macrofactor_` | |
| `BERTBOT_MACROFACTOR_LIVE_TEST` | `false` | `false` | `false` | Test-only |
| `BERTBOT_MACROFACTOR_LIVE_TOOL` | — | `` | `` | Test-only |
| `BERTBOT_MACROFACTOR_LIVE_ARGS_JSON` | — | `{}` | `{}` | Test-only |
| `BERTBOT_MACROFACTOR_EXPECTED_TOOL` | — | `` | `` | Test-only |
| `BERTBOT_MACROFACTOR_EXPECTED_ARG` | — | `` | `` | Test-only |
| `BERTBOT_GOOGLE_WORKSPACE_ENABLED` | `false` | `false` | `false` | |
| `BERTBOT_GOOGLE_WORKSPACE_COMMAND` | `npx` | `npx` | `npx` | |
| `BERTBOT_GOOGLE_WORKSPACE_ARGS` | `…workspace#v0.0.8…` | same | same | |
| `BERTBOT_GOOGLE_WORKSPACE_TIMEOUT_SECONDS` | `60` | `60` | `60` | |
| `BERTBOT_GOOGLE_WORKSPACE_TOOL_NAME_PREFIX` | `google_workspace_` | `google_workspace_` | `google_workspace_` | |
| `BERTBOT_DESKTOP_AUTOMATION_ENABLED` | `false` | `false` | `false` | Enable the desktop-automation MCP bridge |
| `BERTBOT_DESKTOP_AUTOMATION_COMMAND` | `npx` | `npx` | `npx` | |
| `BERTBOT_DESKTOP_AUTOMATION_ARGS` | — | `` | `` | Comma-separated launch args |
| `BERTBOT_DESKTOP_AUTOMATION_TIMEOUT_SECONDS` | `60` | `60` | `60` | |
| `BERTBOT_DESKTOP_AUTOMATION_TOOL_NAME_PREFIX` | `desktop_automation_` | `desktop_automation_` | `desktop_automation_` | |
| `BERTBOT_SHOPPING_ENABLED` | `false` | `false` | `false` | |
| `BERTBOT_SHOPPING_BUDGET_LIMIT_CENTS` | `10000` | `10000` | `10000` | |
| `BERTBOT_SHOPPING_MIN_SELLER_TRUST_SCORE` | `0.7` | `0.7` | `0.7` | |
| `BERTBOT_SHOPPING_STORE_N_ENABLED` | `false` | commented out | commented out | N = 1–9 |
| `BERTBOT_SHOPPING_STORE_N_MODE` | `browse` | commented out | commented out | |
| `BERTBOT_SHOPPING_STORE_N_PRIORITY` | `100` | commented out | commented out | |
| `BERTBOT_SHOPPING_STORE_N_REGION` | — | commented out | commented out | |
| `BERTBOT_SHOPPING_STORE_N_CURRENCY` | — | commented out | commented out | |
| `BERTBOT_PLAYWRIGHT_STORE_ENABLED` | `false` | `false` | `false` | |
| `BERTBOT_PLAYWRIGHT_STORE_DEFAULT_MODE` | `api` | `api` | `api` | `api`, `browser`, or `hybrid` |
| `BERTBOT_PLAYWRIGHT_STORE_MODES` | — | `` | `` | Per-store overrides |
| `BERTBOT_PLAYWRIGHT_STORE_ALLOWED_BROWSER_ACTIONS` | built-in set | `` | `` | Blank = use built-in defaults |
| `BERTBOT_STATE_STORE` | `file` | `file` | `postgres` | [compose override] |
| `BERTBOT_JSON_CODEC` | `gson` | `gson` | `gson` | `gson` or `kotlinx` |
| `BERTBOT_STATE_FILE_PATH` | `state/bertbot-state.json` | same | same | |
| `BERTBOT_MEMORY_EPISODIC_FILE_PATH` | `state/bertbot-memory.txt` | same | same | |
| `BERTBOT_MEMORY_SEMANTIC_FILE_PATH` | `state/bertbot-semantic-memory.txt` | same | same | |
| `BERTBOT_PROFILE_FILE_PATH` | `state/bertbot-profile.json` | same | same | |
| `BERTBOT_INGESTION_CONSENT_FILE_PATH` | `state/bertbot-ingestion-consent.json` | same | same | |
| `BERTBOT_INGESTION_SOURCE_STATE_FILE_PATH` | `state/bertbot-ingestion-source-state.json` | same | same | |
| `BERTBOT_RESEARCH_RECOMMENDATIONS_FILE_PATH` | `state/bertbot-research-recommendations.json` | same | same | |
| `BERTBOT_TRACE_FILE_PATH` | `logs/bertbot-trace.jsonl` | same | same | |
| `BERTBOT_INTERACTIONS_FILE_PATH` | `state/bertbot-interactions.mmd` | same | same | |
| `BERTBOT_CHECKPOINT_AUTOSAVE_ENABLED` | `false` | `false` | `false` | |
| `BERTBOT_CHECKPOINT_FILE_PATH` | `state/bertbot-checkpoints.json` | same | same | |
| `BERTBOT_EVENT_SOURCING_ENABLED` | `false` | `false` | `false` | |
| `BERTBOT_STATE_EVENT_FILE_PATH` | `state/bertbot-state-events.json` | same | same | |
| `BERTBOT_STATE_JDBC_URL` | — | `` | `jdbc:postgresql://postgres:5432/bertbot` | [compose override] |
| `BERTBOT_STATE_JDBC_USER` | — | `` | `bertbot` | [compose override] |
| `BERTBOT_STATE_JDBC_PASSWORD` | — | `` | `bertbot` | [compose override] |
| `BERTBOT_STATE_JDBC_TABLE` | `bertbot_state_snapshot` | same | same | |
| `BERTBOT_CHECKPOINT_JDBC_TABLE` | `bertbot_checkpoint_snapshot` | same | same | |
| `BERTBOT_STATE_EVENT_JDBC_TABLE` | `bertbot_state_event` | same | same | |
| `BERTBOT_MEMORY_EPISODIC_JDBC_TABLE` | `bertbot_memory_episodic_snapshot` | same | same | |
| `BERTBOT_MEMORY_SEMANTIC_JDBC_TABLE` | `bertbot_memory_semantic_snapshot` | same | same | |
| `BERTBOT_PROFILE_JDBC_TABLE` | `bertbot_profile_snapshot` | same | same | |
| `BERTBOT_INGESTION_CONSENT_JDBC_TABLE` | `bertbot_ingestion_consent_snapshot` | same | same | |
| `BERTBOT_INGESTION_SOURCE_STATE_JDBC_TABLE` | `bertbot_ingestion_source_state_snapshot` | same | same | |
| `BERTBOT_RUNTIME_ENV` | `dev` | `dev` | `production` | [compose override] |
| `BERTBOT_CHECKPOINT_ROLLBACK_ENABLED` | `true` | `true` | `true` | |
| `BERTBOT_CHECKPOINT_ROLLBACK_REQUIRE_CONFIRM` | `true` | `true` | `true` | |
| `BERTBOT_CHECKPOINT_ROLLBACK_ALLOW_PROTECTED` | `false` | `false` | `false` | Set `true` to allow rollback in prod/staging |
| `BERTBOT_KOOG_CHAT_MEMORY_ENABLED` | `true` | `true` | `true` | |
| `BERTBOT_KOOG_CHAT_MEMORY_WINDOW_SIZE` | `50` | `50` | `50` | |
| `BERTBOT_KOOG_LONG_TERM_MEMORY_ENABLED` | `true` | `true` | `true` | |
| `BERTBOT_KOOG_LONG_TERM_MEMORY_TOP_K` | `5` | `5` | `5` | |
| `BERTBOT_KOOG_OTEL_SERVICE_NAME` | `personalagent-bertbot` | same | same | |
| `BERTBOT_KOOG_OTEL_SERVICE_VERSION` | `0.1.0` | same | same | |
| `BERTBOT_KOOG_OTEL_VERBOSE` | `false` | `false` | `false` | |
| `BERTBOT_KOOG_OTEL_OTLP_ENDPOINT` | — | `` | `` | Blank disables OTel export |
| `BERTBOT_RESEARCH_ENABLED` | `true` | `true` | `true` | |
| `BERTBOT_RESEARCH_EVENT_DRIVEN_ENABLED` | `true` | `true` | `true` | |
| `BERTBOT_RESEARCH_PERIODIC_ENABLED` | `true` | `true` | `true` | |
| `BERTBOT_RESEARCH_LLM_ASSISTED_ENABLED` | `true` | `true` | `true` | |
| `BERTBOT_RESEARCH_INCLUDE_EXTERNAL_SIGNALS` | `true` | `true` | `true` | |
| `BERTBOT_RESEARCH_PERIODIC_INTERVAL_SECONDS` | `21600` | `21600` | `21600` | |
| `BERTBOT_RESEARCH_MIN_INTERVAL_SECONDS` | `300` | `300` | `300` | |
| `BERTBOT_RESEARCH_MAX_RECOMMENDATIONS_PER_CYCLE` | `8` | `8` | `8` | |
| `BERTBOT_RESEARCH_FAILURE_COOLDOWN_SECONDS` | `900` | `900` | `900` | |
| `BERTBOT_POLYMARKET_GAMMA_BASE_URL` | `https://gamma-api.polymarket.com` | same | same | |
| `BERTBOT_POLYMARKET_CLOB_BASE_URL` | `https://clob.polymarket.com` | same | same | |
| `BERTBOT_POLYMARKET_DATA_BASE_URL` | `https://data-api.polymarket.com` | same | same | |
| `BERTBOT_WEBHOOK_HOST` | `0.0.0.0` | `0.0.0.0` | `0.0.0.0` | Also hardcoded in compose services |
| `BERTBOT_WEBHOOK_PORT` | `8088` | `8088` | `8088` | Also hardcoded in compose services |
| `BERTBOT_WEBHOOK_TELEGRAM_PATH` | `/webhook/telegram` | same | same | |
| `BERTBOT_WEBHOOK_SLACK_PATH` | `/webhook/slack` | same | same | |
| `BERTBOT_WEBHOOK_WHATSAPP_PATH` | `/webhook/whatsapp` | same | same | |
| `BERTBOT_WEBHOOK_HEALTH_PATH` | `/health` | same | same | |
| `BERTBOT_WEBHOOK_DRY_RUN` | `false` | `false` | `false` | |
| `BERTBOT_WEBHOOK_REQUIRE_SIGNATURES` | `false` | `false` | `false` | Set `true` in production |
| `BERTBOT_WEBHOOK_TRUST_PROXY_HEADERS` | `false` | `false` | `false` | |
| `BERTBOT_WEBHOOK_ALLOWED_IPS` | — | `` | `` | |
| `BERTBOT_WEBHOOK_RATE_LIMIT_WINDOW_SECONDS` | `60` | `60` | `60` | |
| `BERTBOT_WEBHOOK_RATE_LIMIT_MAX_REQUESTS` | `120` | `120` | `120` | |
| `BERTBOT_TELEGRAM_ENABLED` | `true` | `true` | `true` | |
| `BERTBOT_SLACK_ENABLED` | `true` | `false` | `false` | |
| `BERTBOT_WHATSAPP_ENABLED` | `true` | `false` | `false` | |
| `BERTBOT_DISCORD_ENABLED` | `false` | `false` | `false` | |
| `BERTBOT_TELEGRAM_API_BASE_URL` | `https://api.telegram.org` | `` | `` | Optional custom Telegram server |
| `BERTBOT_TELEGRAM_BOT_TOKEN` | — | `[required]` | `[required]` | |
| `BERTBOT_SLACK_BOT_TOKEN` | — | placeholder | placeholder | Required when Slack enabled |
| `BERTBOT_WHATSAPP_ACCESS_TOKEN` | — | placeholder | placeholder | Required when WhatsApp enabled |
| `BERTBOT_WHATSAPP_API_VERSION` | `v22.0` | `v22.0` | `v22.0` | |
| `BERTBOT_DISCORD_BOT_TOKEN` | — | placeholder | placeholder | Required when Discord enabled |
| `BERTBOT_DISCORD_GUILD_ID` | — | `` | `` | |
| `BERTBOT_DISCORD_APPROVED_CHANNEL_IDS` | — | `` | `` | |
| `BERTBOT_DISCORD_APPROVED_DIRECT_MESSAGE_IDS` | — | `` | `` | |
| `BERTBOT_SLACK_WORKSPACE_ID` | — | `` | `` | |
| `BERTBOT_WHATSAPP_BUSINESS_PHONE_ID` | — | `` | `` | |
| `BERTBOT_INGESTION_REQUIRE_APPROVAL` | `true` | `false` | `false` | |
| `BERTBOT_TELEGRAM_SECRET_TOKEN` | — | placeholder | placeholder | Required when signatures enabled |
| `BERTBOT_SLACK_SIGNING_SECRET` | — | placeholder | placeholder | Required when signatures enabled |
| `BERTBOT_SLACK_MAX_REQUEST_AGE_SECONDS` | `300` | `300` | `300` | |
| `BERTBOT_WHATSAPP_APP_SECRET` | — | placeholder | placeholder | Required when signatures enabled |
| `BERTBOT_WHATSAPP_VERIFY_TOKEN` | — | placeholder | placeholder | Required for WhatsApp subscription |

## Minimal Local Setup

OpenAI example:

```bash
BERTBOT_AI_PROVIDER=openai
BERTBOT_AI_MODEL=gpt-4o-mini
BERTBOT_AI_API_KEY=your-api-key-here
```

Ollama example:

```bash
BERTBOT_AI_PROVIDER=ollama
BERTBOT_AI_MODEL=llama3.1
BERTBOT_OLLAMA_BASE_URL=http://localhost:11434
```

## Core Runtime Settings

| Variable | Purpose | Notes |
| --- | --- | --- |
| `BERTBOT_RUN_MODE` | Container entrypoint mode | `webhook`, `mcp`, `headless`, `interactive`, `discord`; default `webhook` |
| `BERTBOT_AI_PROVIDER` | Active LLM provider | `openai` or `ollama` |
| `BERTBOT_AI_MODEL` | Model name for the active provider | Keep aligned with the selected backend |
| `BERTBOT_AI_API_KEY` | OpenAI API key | Required for `openai` |
| `BERTBOT_OLLAMA_BASE_URL` | Ollama server URL | Default `http://localhost:11434` |
| `BERTBOT_OLLAMA_TIMEOUT_SECONDS` | Ollama request timeout | Default `120` |
| `BERTBOT_WORKSPACE_ROOT` | Workspace tool root override | Useful for MCP workspace tool routes |

## Model Routing And Incident Recovery

Phase 2 and Phase 3 routing are deterministic graph stages and currently use in-repo config defaults through `BertBotAgentConfig`.

- `modelSelection.primaryModel` (default `gpt-4o-mini`) routes lower-complexity tasks to lower-cost inference.
- `modelSelection.reasoningModel` (default `gpt-4o`) routes higher-complexity tasks when research, urgency, or evidence density signals are high.
- `modelSelection.costBudgetPerRequestUsd` (default `0.50`) emits cost budget warnings in traces when estimated request cost exceeds the threshold.

Incident response runs after execution:

- `IncidentDetectorNode` identifies low-evidence, missing-model, delegation, and approval-gated incidents.
- `IncidentCommanderNode` applies bounded recovery actions (`RETRY`, `FALLBACK`, `ESCALATE`, `ABORT`) and records incident log entries in state snapshots.

These settings are currently code-configured (constructor/config object) rather than environment-variable controlled.

## Proxy Tool Integrations

Google Workspace MCP proxy:

| Variable | Purpose |
| --- | --- |
| `BERTBOT_GOOGLE_WORKSPACE_ENABLED` | Enable Google Workspace proxy tool registration |
| `BERTBOT_GOOGLE_WORKSPACE_COMMAND` | Executable used to launch the upstream MCP server |
| `BERTBOT_GOOGLE_WORKSPACE_ARGS` | Comma-separated launch args |
| `BERTBOT_GOOGLE_WORKSPACE_TIMEOUT_SECONDS` | Upstream response timeout |
| `BERTBOT_GOOGLE_WORKSPACE_TOOL_NAME_PREFIX` | Tool-name prefix exposed by BertBot |

MacroFactor MCP proxy:

| Variable | Purpose |
| --- | --- |
| `BERTBOT_MACROFACTOR_ENABLED` | Enable MacroFactor proxy tool registration |
| `BERTBOT_MACROFACTOR_COMMAND` | Executable used to launch MacroFactor MCP |
| `BERTBOT_MACROFACTOR_ARGS` | Comma-separated launch args |
| `BERTBOT_MACROFACTOR_USERNAME` | MacroFactor username or email |
| `BERTBOT_MACROFACTOR_PASSWORD` | MacroFactor password |
| `BERTBOT_MACROFACTOR_TIMEOUT_SECONDS` | Upstream response timeout |
| `BERTBOT_MACROFACTOR_TOOL_NAME_PREFIX` | Tool-name prefix exposed by BertBot |
| `BERTBOT_MACROFACTOR_LIVE_TEST` | Enable opt-in live integration tests |
| `BERTBOT_MACROFACTOR_LIVE_TOOL` | Optional upstream tool for live test calls |
| `BERTBOT_MACROFACTOR_LIVE_ARGS_JSON` | Optional live test tool arguments |
| `BERTBOT_MACROFACTOR_EXPECTED_TOOL` | Optional `tools/list` assertion target |
| `BERTBOT_MACROFACTOR_EXPECTED_ARG` | Optional schema assertion key |

Desktop automation MCP bridge:

| Variable | Purpose |
| --- | --- |
| `BERTBOT_DESKTOP_AUTOMATION_ENABLED` | Enable the desktop-automation proxy tool registration |
| `BERTBOT_DESKTOP_AUTOMATION_COMMAND` | Executable used to launch the upstream desktop automation bridge |
| `BERTBOT_DESKTOP_AUTOMATION_ARGS` | Comma-separated launch args |
| `BERTBOT_DESKTOP_AUTOMATION_TIMEOUT_SECONDS` | Upstream response timeout |
| `BERTBOT_DESKTOP_AUTOMATION_TOOL_NAME_PREFIX` | Tool-name prefix exposed by BertBot |

Shopping store configuration (supports up to 9 numbered stores; replace `N` with 1–9):

| Variable | Purpose | Notes |
| --- | --- | --- |
| `BERTBOT_SHOPPING_ENABLED` | Master toggle for the personal-shopper sub-agent | Default `false`; must be `true` to activate |
| `BERTBOT_SHOPPING_BUDGET_LIMIT_CENTS` | Maximum spend per order in cents | Default `10000`; `-1` for unlimited |
| `BERTBOT_SHOPPING_MIN_SELLER_TRUST_SCORE` | Minimum seller trust score (0.0–1.0) | Default `0.7` |
| `BERTBOT_SHOPPING_STORE_N_ENABLED` | Enable this shopping store slot | Required to activate the slot; default `false` |
| `BERTBOT_SHOPPING_STORE_N_MODE` | Operating mode for the store | e.g. `browse`; default `browse` |
| `BERTBOT_SHOPPING_STORE_N_PRIORITY` | Sort priority (lower = higher precedence) | Default `100` |
| `BERTBOT_SHOPPING_STORE_N_REGION` | Region code for locale-aware queries | e.g. `us`; optional |
| `BERTBOT_SHOPPING_STORE_N_CURRENCY` | Currency code for price display | e.g. `usd`; optional |

Enabling the `personal_shopper` sub-agent without at least one store slot set to `ENABLED=true` causes startup to fail with a clear error message. Shopping actions that modify state (cart, order) always require explicit user confirmation; autonomous final checkout is never performed.

Playwright browser store adapter:

The Playwright store adapter is **disabled by default**. It adds an optional web-browser automation fallback for external stores where the API is missing or unstable. It is intentionally web-only and safety-constrained; it does not provide general desktop or OS-level automation.

Three modes are available per store:

- `api` – API-only execution (default).
- `browser` – Browser-only execution via Playwright.
- `hybrid` – Try API first; fall back to browser on failure.

Browser actions are restricted to an explicit allowlist. When both the API and browser paths fail, the adapter returns a safe, user-facing recommendation instead of an unrecoverable error.

| Variable | Purpose |
| --- | --- |
| `BERTBOT_PLAYWRIGHT_STORE_ENABLED` | Enable the Playwright store adapter (default `false`) |
| `BERTBOT_PLAYWRIGHT_STORE_DEFAULT_MODE` | Default mode for all stores: `api`, `browser`, or `hybrid` (default `api`) |
| `BERTBOT_PLAYWRIGHT_STORE_MODES` | Per-store mode overrides as `store1:hybrid,store2:browser` |
| `BERTBOT_PLAYWRIGHT_STORE_ALLOWED_BROWSER_ACTIONS` | Comma-separated allowlist of browser actions (default: `navigate,click,fill,read,screenshot,select,hover,scroll`) |

## Persistence Settings

Backend selection:

| Variable | Purpose | Notes |
| --- | --- | --- |
| `BERTBOT_STATE_STORE` | Select persistence backend | `file` by default; `jdbc`, `postgres`, `postgresql` for deployed environments |
| `BERTBOT_JSON_CODEC` | JSON serialisation codec | `gson` (default) or `kotlinx` |

File-backed paths:

- `BERTBOT_STATE_FILE_PATH`
- `BERTBOT_CHECKPOINT_FILE_PATH`
- `BERTBOT_MEMORY_EPISODIC_FILE_PATH`
- `BERTBOT_MEMORY_SEMANTIC_FILE_PATH`
- `BERTBOT_PROFILE_FILE_PATH`
- `BERTBOT_INGESTION_CONSENT_FILE_PATH`
- `BERTBOT_INGESTION_SOURCE_STATE_FILE_PATH`
- `BERTBOT_RESEARCH_RECOMMENDATIONS_FILE_PATH`
- `BERTBOT_TRACE_FILE_PATH`
- `BERTBOT_INTERACTIONS_FILE_PATH`
- `BERTBOT_STATE_EVENT_FILE_PATH` – event-sourcing log (used when `BERTBOT_EVENT_SOURCING_ENABLED=true`)

JDBC or PostgreSQL settings:

- `BERTBOT_STATE_JDBC_URL`
- `BERTBOT_STATE_JDBC_USER`
- `BERTBOT_STATE_JDBC_PASSWORD`
- `BERTBOT_STATE_JDBC_TABLE`
- `BERTBOT_CHECKPOINT_JDBC_TABLE` – defaults to `bertbot_checkpoint_snapshot`
- `BERTBOT_STATE_EVENT_JDBC_TABLE` – defaults to `bertbot_state_event`
- `BERTBOT_MEMORY_EPISODIC_JDBC_TABLE`
- `BERTBOT_MEMORY_SEMANTIC_JDBC_TABLE`
- `BERTBOT_PROFILE_JDBC_TABLE`
- `BERTBOT_INGESTION_CONSENT_JDBC_TABLE`
- `BERTBOT_INGESTION_SOURCE_STATE_JDBC_TABLE`

Local Gradle runs can stay on the default file backend. Containerised runs should prefer PostgreSQL-backed persistence. See [deployment.md](deployment.md).

## Checkpoint And Event Sourcing

| Variable | Purpose | Notes |
| --- | --- | --- |
| `BERTBOT_CHECKPOINT_AUTOSAVE_ENABLED` | Auto-save a checkpoint after each run | Default `false` |
| `BERTBOT_CHECKPOINT_FILE_PATH` | File path for checkpoint snapshots | Default `state/bertbot-checkpoints.json` |
| `BERTBOT_CHECKPOINT_JDBC_TABLE` | JDBC table for checkpoint snapshots | Default `bertbot_checkpoint_snapshot` |
| `BERTBOT_EVENT_SOURCING_ENABLED` | Append state-change events to an event log | Default `false` |
| `BERTBOT_STATE_EVENT_FILE_PATH` | File path for the state event log | Default `state/bertbot-state-events.json` |
| `BERTBOT_STATE_EVENT_JDBC_TABLE` | JDBC table for state events | Default `bertbot_state_event` |

## Runtime Environment And Checkpoint Rollback

| Variable | Purpose | Notes |
| --- | --- | --- |
| `BERTBOT_RUNTIME_ENV` | Runtime environment tag | `dev` (default), `staging`, `production`; affects rollback protection |
| `BERTBOT_CHECKPOINT_ROLLBACK_ENABLED` | Allow checkpoint rollback via MCP tool | Default `true` |
| `BERTBOT_CHECKPOINT_ROLLBACK_REQUIRE_CONFIRM` | Require user confirmation before rollback | Default `true` |
| `BERTBOT_CHECKPOINT_ROLLBACK_ALLOW_PROTECTED` | Allow rollback in production or staging environments | Default `false`; set `true` only to override the protection |

When `BERTBOT_RUNTIME_ENV` is `prod`, `production`, `staging`, or `preprod`, rollback is blocked unless `BERTBOT_CHECKPOINT_ROLLBACK_ALLOW_PROTECTED=true`.

## Koog Integration Settings

| Variable | Purpose | Notes |
| --- | --- | --- |
| `BERTBOT_KOOG_CHAT_MEMORY_ENABLED` | Enable in-context chat memory window | Default `true` |
| `BERTBOT_KOOG_CHAT_MEMORY_WINDOW_SIZE` | Number of past turns to include | Default `50` |
| `BERTBOT_KOOG_LONG_TERM_MEMORY_ENABLED` | Enable long-term memory retrieval | Default `true` |
| `BERTBOT_KOOG_LONG_TERM_MEMORY_TOP_K` | Number of memories to retrieve per turn | Default `5` |
| `BERTBOT_KOOG_OTEL_SERVICE_NAME` | OTel service name for traces | Default `personalagent-bertbot` |
| `BERTBOT_KOOG_OTEL_SERVICE_VERSION` | OTel service version | Default `0.1.0` |
| `BERTBOT_KOOG_OTEL_VERBOSE` | Enable verbose OTel output | Default `false` |
| `BERTBOT_KOOG_OTEL_OTLP_ENDPOINT` | OTLP exporter endpoint URL | Leave blank to disable OTel export |

## Research Overrides

- `BERTBOT_RESEARCH_ENABLED`
- `BERTBOT_RESEARCH_EVENT_DRIVEN_ENABLED`
- `BERTBOT_RESEARCH_PERIODIC_ENABLED`
- `BERTBOT_RESEARCH_LLM_ASSISTED_ENABLED`
- `BERTBOT_RESEARCH_INCLUDE_EXTERNAL_SIGNALS`
- `BERTBOT_RESEARCH_PERIODIC_INTERVAL_SECONDS`
- `BERTBOT_RESEARCH_MIN_INTERVAL_SECONDS`
- `BERTBOT_RESEARCH_MAX_RECOMMENDATIONS_PER_CYCLE`
- `BERTBOT_RESEARCH_FAILURE_COOLDOWN_SECONDS`

## Polymarket Endpoints

- `BERTBOT_POLYMARKET_GAMMA_BASE_URL`
- `BERTBOT_POLYMARKET_CLOB_BASE_URL`
- `BERTBOT_POLYMARKET_DATA_BASE_URL`

## Sub-Agent Defaults And Opt-In Strategy

Default guidance for production-style setups:

- Keep broadly useful sub-agents enabled by default (coding, planning, architecture, analysis, safety, workspace operations).
- Keep niche or domain-specific sub-agents opt-in by default.

Current opt-in sub-agents in default config:

- `polymarket_analyst` (disabled by default).
- `personal_shopper` (disabled by default, requires shopping store configuration).

When enabling a niche sub-agent, pair that change with:

1. A clear use case that is expected to recur.
2. A matching verification or routing test update.
3. Capability status validation so user-facing availability remains accurate.

### Opt-In Examples

There is no dedicated environment-variable switch for individual `subAgents`; opt-in is done through a `BertBotAgentConfig` override.

Enable `polymarket_analyst` for local development:

```kotlin
val config =
  BertBotAgentConfig(
    subAgents =
      BertBotAgentConfig().subAgents.map { definition ->
        if (definition.id == "polymarket_analyst") definition.copy(enabled = true) else definition
      },
  )
```

Production-style guidance:

- Keep `polymarket_analyst` disabled unless Polymarket analysis is a recurring requirement.
- Keep `personal_shopper` disabled unless shopping stores are configured and explicitly needed.
- If you enable either, update tests that assert enabled or disabled sub-agent defaults.

## Webhook Runtime Settings

| Variable | Purpose |
| --- | --- |
| `BERTBOT_WEBHOOK_HOST` | Bind host for the webhook server |
| `BERTBOT_WEBHOOK_PORT` | Bind port for the webhook server |
| `BERTBOT_WEBHOOK_TELEGRAM_PATH` | Telegram webhook path |
| `BERTBOT_WEBHOOK_SLACK_PATH` | Slack webhook path |
| `BERTBOT_WEBHOOK_WHATSAPP_PATH` | WhatsApp webhook path |
| `BERTBOT_WEBHOOK_HEALTH_PATH` | Health endpoint path |
| `BERTBOT_WEBHOOK_DRY_RUN` | Process without persistence writes |
| `BERTBOT_WEBHOOK_REQUIRE_SIGNATURES` | Enforce provider request verification |
| `BERTBOT_WEBHOOK_TRUST_PROXY_HEADERS` | Trust forwarded IP headers |
| `BERTBOT_WEBHOOK_ALLOWED_IPS` | Allowlist of client IPs and CIDRs |
| `BERTBOT_WEBHOOK_RATE_LIMIT_WINDOW_SECONDS` | Rate-limit window size |
| `BERTBOT_WEBHOOK_RATE_LIMIT_MAX_REQUESTS` | Max requests per client in the window |

If `BERTBOT_WEBHOOK_PORT` is unset, the runtime falls back to the platform `PORT` environment variable used by Cloud Run.

## Connector Enablement

| Variable | Purpose |
| --- | --- |
| `BERTBOT_TELEGRAM_ENABLED` | Enable Telegram adapter wiring |
| `BERTBOT_SLACK_ENABLED` | Enable Slack adapter wiring |
| `BERTBOT_WHATSAPP_ENABLED` | Enable WhatsApp adapter wiring |
| `BERTBOT_DISCORD_ENABLED` | Enable Discord adapter wiring |
| `BERTBOT_SLACK_WORKSPACE_ID` | Slack workspace identifier in normalized metadata |
| `BERTBOT_WHATSAPP_BUSINESS_PHONE_ID` | WhatsApp Business phone identifier |
| `BERTBOT_DISCORD_GUILD_ID` | Restrict Discord handling to a guild |
| `BERTBOT_DISCORD_APPROVED_CHANNEL_IDS` | Allowlisted Discord channel IDs |
| `BERTBOT_DISCORD_APPROVED_DIRECT_MESSAGE_IDS` | Allowlisted Discord DM channel IDs |
| `BERTBOT_DISCORD_BOT_TOKEN` | Discord bot token |
| `BERTBOT_INGESTION_REQUIRE_APPROVAL` | Require explicit source approval before ingestion |

## Provider Verification

Used when `BERTBOT_WEBHOOK_REQUIRE_SIGNATURES=true`:

| Variable | Purpose |
| --- | --- |
| `BERTBOT_TELEGRAM_SECRET_TOKEN` | Expected Telegram secret-token header value |
| `BERTBOT_SLACK_SIGNING_SECRET` | Slack signing secret |
| `BERTBOT_SLACK_MAX_REQUEST_AGE_SECONDS` | Allowed Slack timestamp skew |
| `BERTBOT_WHATSAPP_APP_SECRET` | Meta app secret for HMAC verification |
| `BERTBOT_WHATSAPP_VERIFY_TOKEN` | WhatsApp subscription verification token |

## Shopping Workflow Configuration

Set `BERTBOT_SHOPPING_ENABLED=true` and configure at least one store slot (`BERTBOT_SHOPPING_STORE_1_ENABLED=true`, etc.) to activate shopping. Startup fails with a clear error if the sub-agent is enabled without any active store slot.

BertBot handles shopping assistance stages (onboarding, recommendation, compare, cart_prepare, checkout_prepare) through the standard agent pipeline.

Shopping safety invariants are enforced at the agent level:

- `cart_prepare` and `checkout_prepare` always request explicit user confirmation before any state change.
- Budget (`BERTBOT_SHOPPING_BUDGET_LIMIT_CENTS`) and seller-threshold (`BERTBOT_SHOPPING_MIN_SELLER_TRUST_SCORE`) constraints are applied before any order action.
- Final checkout is never performed autonomously.

To enable Playwright browser automation as a shopping fallback, ensure the coder sub-agent is enabled in `BertBotAgentConfig` and the Playwright MCP or tool is available at runtime. The `RuntimeCapabilitySnapshot` reports `playwrightFallbackAvailable` separately from the sub-agent advertisement; set this to `true` only when a direct Playwright integration is wired into the runtime.

## Cloud Secret Wiring

When deploying to Cloud Run or a similar platform, inject secrets as environment variables from your secret manager. The following variables contain credentials and must never be hardcoded:

| Variable | Secret type |
| --- | --- |
| `BERTBOT_AI_API_KEY` | OpenAI API key |
| `BERTBOT_STATE_JDBC_URL` | Full JDBC connection string (may embed password) |
| `BERTBOT_STATE_JDBC_USER` | Database username |
| `BERTBOT_STATE_JDBC_PASSWORD` | Database password |
| `BERTBOT_TELEGRAM_SECRET_TOKEN` | Telegram webhook secret |
| `BERTBOT_SLACK_SIGNING_SECRET` | Slack signing secret |
| `BERTBOT_WHATSAPP_APP_SECRET` | WhatsApp HMAC app secret |
| `BERTBOT_MACROFACTOR_USERNAME` | MacroFactor account email |
| `BERTBOT_MACROFACTOR_PASSWORD` | MacroFactor account password |

Cloud Run example (gcloud):

```bash
gcloud run deploy bertbot \
  --set-secrets "BERTBOT_AI_API_KEY=bertbot-openai-key:latest" \
  --set-secrets "BERTBOT_STATE_JDBC_URL=bertbot-jdbc-url:latest" \
  --set-secrets "BERTBOT_STATE_JDBC_USER=bertbot-jdbc-user:latest" \
  --set-secrets "BERTBOT_STATE_JDBC_PASSWORD=bertbot-jdbc-password:latest" \
  --set-secrets "BERTBOT_TELEGRAM_SECRET_TOKEN=bertbot-telegram-secret:latest"
```

Local development with `.env` (never commit this file):

```bash
BERTBOT_AI_API_KEY=sk-...
BERTBOT_STATE_STORE=file
BERTBOT_STATE_JDBC_URL=
BERTBOT_STATE_JDBC_USER=
BERTBOT_STATE_JDBC_PASSWORD=
```

## Practical Config Profiles

See [Environment File Usage](#environment-file-usage) for the authoritative mapping of files to runtime contexts.

Local CLI or MCP development:

- Copy [../.env.example](../.env.example) to `.env` (one-time) and set `BERTBOT_AI_API_KEY`.
- Keep `BERTBOT_STATE_STORE=file`.
- Launch all commands from the repository root so workspace-relative paths resolve correctly.
- Do **not** use `.env.compose` for local Gradle runs.

Webhook deployment (local):

- Use `.env` from the repository root.
- Set `BERTBOT_WEBHOOK_REQUIRE_SIGNATURES=true` and configure connector-specific verification secrets.
- Use PostgreSQL-backed persistence.
- Set `BERTBOT_RUNTIME_ENV=production` to enable checkpoint rollback protection.

Docker Compose deployment:

- Copy [../.env.compose.example](../.env.compose.example) to `.env.compose` (one-time).
- Do **not** use `.env` for compose runs; `docker-compose.yml` loads `.env.compose` via `env_file`.
- Keep runtime mode and persistence backend aligned with the service role (`BERTBOT_STATE_STORE=postgres`).
- Fill in `BERTBOT_AI_API_KEY` and connector credentials.
- Keys annotated `[compose override]` in the template differ from `.env.example`; all others are identical.

Cloud Run or hosted deployment:

- Do **not** use a local env file; inject all variables through Secret Manager or the platform environment mechanism.
- See [deployment.md](deployment.md) for Cloud Run secret wiring details.
