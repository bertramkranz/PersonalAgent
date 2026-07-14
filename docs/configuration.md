# Configuration

BertBot reads configuration from process environment variables first and then falls back to `.env` in the repository root.

To bootstrap local development, copy [../.env.example](../.env.example) to `.env` and set your provider-specific values.

Template defaults in [../.env.example](../.env.example) are intentionally conservative:

- `BERTBOT_GOOGLE_WORKSPACE_ENABLED=false`
- `BERTBOT_SLACK_ENABLED=false`
- `BERTBOT_WHATSAPP_ENABLED=false`
- `BERTBOT_INGESTION_REQUIRE_APPROVAL=false`

This keeps local startup simple while still allowing immediate Telegram webhook replies when enabled.

See [run-modes.md](run-modes.md) for runtime-specific commands, [deployment.md](deployment.md) for Docker Compose and Cloud Run guidance, and [vscode-copilot.md](vscode-copilot.md) for workspace MCP setup.

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

## Persistence Settings

Backend selection:

| Variable | Purpose | Notes |
| --- | --- | --- |
| `BERTBOT_STATE_STORE` | Select persistence backend | `file` by default; `jdbc`, `postgres`, `postgresql` for deployed environments |

File-backed paths:

- `BERTBOT_STATE_FILE_PATH`
- `BERTBOT_MEMORY_EPISODIC_FILE_PATH`
- `BERTBOT_MEMORY_SEMANTIC_FILE_PATH`
- `BERTBOT_PROFILE_FILE_PATH`
- `BERTBOT_INGESTION_CONSENT_FILE_PATH`
- `BERTBOT_INGESTION_SOURCE_STATE_FILE_PATH`
- `BERTBOT_RESEARCH_RECOMMENDATIONS_FILE_PATH`
- `BERTBOT_TRACE_FILE_PATH`
- `BERTBOT_INTERACTIONS_FILE_PATH`

JDBC or PostgreSQL settings:

- `BERTBOT_STATE_JDBC_URL`
- `BERTBOT_STATE_JDBC_USER`
- `BERTBOT_STATE_JDBC_PASSWORD`
- `BERTBOT_STATE_JDBC_TABLE`
- `BERTBOT_MEMORY_EPISODIC_JDBC_TABLE`
- `BERTBOT_MEMORY_SEMANTIC_JDBC_TABLE`
- `BERTBOT_PROFILE_JDBC_TABLE`
- `BERTBOT_INGESTION_CONSENT_JDBC_TABLE`
- `BERTBOT_INGESTION_SOURCE_STATE_JDBC_TABLE`

Local Gradle runs can stay on the default file backend. Containerized runs should prefer PostgreSQL-backed persistence. See [deployment.md](deployment.md).

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

BertBot handles shopping assistance stages (onboarding, recommendation, compare, cart_prepare, checkout_prepare) through the standard agent pipeline — no additional variables are required beyond the core LLM and persistence settings.

Shopping safety invariants are enforced at the agent level:

- `cart_prepare` and `checkout_prepare` always request explicit user confirmation before any state change.
- Budget and seller-threshold constraints come from the user profile stored in the configured persistence backend.
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

Local CLI or MCP development:

- Use `.env` from the repository root.
- Keep `BERTBOT_STATE_STORE=file`.
- Launch commands from the repository root so workspace-relative paths resolve correctly.

Webhook deployment:

- Set `BERTBOT_WEBHOOK_REQUIRE_SIGNATURES=true`.
- Configure connector-specific verification secrets.
- Use PostgreSQL-backed persistence.

Container deployment:

- Start from [../.env.compose.example](../.env.compose.example).
- Keep runtime mode and persistence aligned with the service role.

