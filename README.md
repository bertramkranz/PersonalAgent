# BertBot

BertBot is a personal assistant orchestration agent built with a graph-based structure so it stays composable, inspectable, and easy to extend.

## Architecture

The project is organized around a small set of focused packages:

- `com.personalagent.bertbot.app` - application entrypoint and runtime wiring.
- `com.personalagent.bertbot.config` - BertBot persona, prompt, tool, and skill configuration.
- `com.personalagent.bertbot.llm` - LLM gateway contracts and provider adapters.
- `com.personalagent.bertbot.graph.model` - graph state model.
- `com.personalagent.bertbot.graph.nodes` - node implementations and node identifiers.
- `com.personalagent.bertbot.graph.runtime` - graph contracts, edges, definitions, and runner.
- `com.personalagent.bertbot.graph.store` - state store implementations.
- `com.personalagent.bertbot.memory` - lightweight memory storage for learned personal context.
- `com.personalagent.bertbot.ingestion` - external chat ingestion control plane, source approval, and connector adapters.
- `com.personalagent.bertbot.agents` - sub-agent registry and capability matching.

This structure follows Koog-style example conventions by grouping code by capability boundary (runtime, tools, graph, persistence, connectors) instead of by technical layer only. In practice:

- Transport surfaces (`cli`, `mcp`, `webhook`) stay in `app` entrypoint wiring.
- Tool routers are isolated by integration domain (for example Polymarket and MacroFactor).
- Graph contracts remain split across `graph.model`, `graph.nodes`, `graph.runtime`, and `graph.store`.
- Runtime configuration is driven by environment variables with explicit defaults.

## Runtime Flow

BertBot runs as a graph of named nodes:

1. Capture the incoming message.
2. Plan the work and identify priorities.
3. Match the task to a specialized sub-agent when appropriate.
4. Execute the delegated workflow.
5. Persist the resulting execution snapshot to disk.

This structure keeps the orchestration logic easy to visualize and makes it straightforward to add more nodes later.

## Persistence

BertBot currently persists local state in these files:

- `bertbot-state.json` - latest graph execution snapshot and delegation context for inspection/debugging.
- `bertbot-memory.txt` - episodic memory entries from recent interactions.
- `bertbot-semantic-memory.txt` - summarized semantic memory created from episodic history.
- `bertbot-profile.json` - structured profile facts (for example, remembered user name).
- `bertbot-trace.jsonl` - structured runtime trace events with trace IDs.
- `bertbot-interactions.mmd` - Mermaid sequence diagram generated from the latest trace and graph state.

These files are local to the workspace. Memory and profile files retain conversational context across runs, while the state, trace, and Mermaid files capture the latest orchestration run for inspection.

## Extending BertBot

To add a new capability:

- Add a new graph node in `com.personalagent.bertbot.graph.nodes`.
- Register the node in the graph definition in `com.personalagent.bertbot.app.BertBotGraphFactory`.
- Add or update sub-agent definitions in `com.personalagent.bertbot.agents.SubAgentRegistry`.
- Refine the persona, tools, or skills in `com.personalagent.bertbot.config.BertBotAgentConfig`.

Default sub-agent roles currently include: Coder Agent, Planner Agent, Architect Agent, Analyst Agent, Polymarket Analyst, Copywriter Agent, Red Team Agent, Philosopher Agent, and Psychologist Agent.

## Configuration

BertBot reads configuration from the process environment first and falls back to a local `.env` file in the workspace root.

If you are setting up the project from scratch, copy [.env.example](.env.example) to `.env` and fill in your values.

The runtime is provider-aware at the LLM adapter boundary. The repository ships OpenAI and Ollama adapters, and new providers can be added by implementing the `LlmGateway` contract.

The current configuration variables are:

- `BERTBOT_AI_PROVIDER` - selects the active AI provider adapter. Supported values: `openai`, `ollama`.
- `BERTBOT_AI_MODEL` - selects the chat model for the active provider adapter.
- `BERTBOT_AI_API_KEY` - OpenAI API key (required when `BERTBOT_AI_PROVIDER=openai`).
- `BERTBOT_OLLAMA_BASE_URL` - Ollama server base URL (used when `BERTBOT_AI_PROVIDER=ollama`). Default: `http://localhost:11434`.
- `BERTBOT_OLLAMA_TIMEOUT_SECONDS` - Ollama request timeout in seconds. Default: `120`.
- `BERTBOT_WORKSPACE_ROOT` - optional MCP workspace root override used by workspace tool routes.

MacroFactor MCP proxy variables:

- `BERTBOT_MACROFACTOR_ENABLED` - enable MacroFactor MCP proxy tool registration. Default: `false`.
- `BERTBOT_MACROFACTOR_COMMAND` - executable used to launch the MacroFactor MCP server. Default: `npx`.
- `BERTBOT_MACROFACTOR_ARGS` - comma-separated command args used to launch MacroFactor MCP. Default: `-y,sjawhar-macrofactor`.
- `BERTBOT_MACROFACTOR_USERNAME` - MacroFactor account username/email.
- `BERTBOT_MACROFACTOR_PASSWORD` - MacroFactor account password.
- `BERTBOT_MACROFACTOR_TIMEOUT_SECONDS` - timeout for upstream MacroFactor MCP responses. Default: `45`.
- `BERTBOT_MACROFACTOR_TOOL_NAME_PREFIX` - proxy tool name prefix exposed by BertBot. Default: `macrofactor_`.
- `BERTBOT_MACROFACTOR_LIVE_TEST` - enable live integration tests for MacroFactor proxy.
- `BERTBOT_MACROFACTOR_LIVE_TOOL` - optional upstream tool name used by live integration tests.
- `BERTBOT_MACROFACTOR_LIVE_ARGS_JSON` - optional JSON arguments for the live test tool call.
- `BERTBOT_MACROFACTOR_EXPECTED_TOOL` - optional tool expected in `tools/list` assertions.
- `BERTBOT_MACROFACTOR_EXPECTED_ARG` - optional expected argument key for schema assertions.

State persistence backend variables:

- `BERTBOT_STATE_STORE` - state store backend. Supported values: `file` (default), `jdbc`, `postgres`, `postgresql`.
- `BERTBOT_STATE_FILE_PATH` - state snapshot file path when file backend is used. Default: `bertbot-state.json`.
- `BERTBOT_MEMORY_EPISODIC_FILE_PATH` - episodic memory file path when file backend is used. Default: `bertbot-memory.txt`.
- `BERTBOT_MEMORY_SEMANTIC_FILE_PATH` - semantic memory file path when file backend is used. Default: `bertbot-semantic-memory.txt`.
- `BERTBOT_PROFILE_FILE_PATH` - user profile file path when file backend is used. Default: `bertbot-profile.json`.
- `BERTBOT_INGESTION_CONSENT_FILE_PATH` - ingestion consent file path when file backend is used. Default: `bertbot-ingestion-consent.json`.
- `BERTBOT_INGESTION_SOURCE_STATE_FILE_PATH` - ingestion source-state file path when file backend is used. Default: `bertbot-ingestion-source-state.json`.
- `BERTBOT_RESEARCH_RECOMMENDATIONS_FILE_PATH` - continuous-improvement recommendations file path for file backend. Default: `bertbot-research-recommendations.json`.
- `BERTBOT_STATE_JDBC_URL` - JDBC connection URL when JDBC/PostgreSQL backend is used.
- `BERTBOT_STATE_JDBC_USER` - optional JDBC username.
- `BERTBOT_STATE_JDBC_PASSWORD` - optional JDBC password.
- `BERTBOT_STATE_JDBC_TABLE` - graph state snapshot table name. Default: `bertbot_state_snapshot`.
- `BERTBOT_MEMORY_EPISODIC_JDBC_TABLE` - episodic memory snapshot table name. Default: `bertbot_memory_episodic_snapshot`.
- `BERTBOT_MEMORY_SEMANTIC_JDBC_TABLE` - semantic memory snapshot table name. Default: `bertbot_memory_semantic_snapshot`.
- `BERTBOT_PROFILE_JDBC_TABLE` - user profile snapshot table name. Default: `bertbot_profile_snapshot`.
- `BERTBOT_INGESTION_CONSENT_JDBC_TABLE` - ingestion consent snapshot table name. Default: `bertbot_ingestion_consent_snapshot`.
- `BERTBOT_INGESTION_SOURCE_STATE_JDBC_TABLE` - ingestion source-state snapshot table name. Default: `bertbot_ingestion_source_state_snapshot`.

Continuous improvement research overrides:

- `BERTBOT_RESEARCH_ENABLED` - global research pipeline switch.
- `BERTBOT_RESEARCH_EVENT_DRIVEN_ENABLED` - enable event-triggered research runs.
- `BERTBOT_RESEARCH_PERIODIC_ENABLED` - enable periodic scheduler-triggered runs.
- `BERTBOT_RESEARCH_LLM_ASSISTED_ENABLED` - enable LLM-assisted recommendation scoring.
- `BERTBOT_RESEARCH_INCLUDE_EXTERNAL_SIGNALS` - include external signals in recommendation generation.
- `BERTBOT_RESEARCH_PERIODIC_INTERVAL_SECONDS` - periodic scheduler interval in seconds.
- `BERTBOT_RESEARCH_MIN_INTERVAL_SECONDS` - minimum cool-off between research runs.
- `BERTBOT_RESEARCH_MAX_RECOMMENDATIONS_PER_CYCLE` - max recommendations emitted per cycle.
- `BERTBOT_RESEARCH_FAILURE_COOLDOWN_SECONDS` - cool-off after failed research cycles.

Polymarket endpoint overrides:

- `BERTBOT_POLYMARKET_GAMMA_BASE_URL` - Gamma API base URL.
- `BERTBOT_POLYMARKET_CLOB_BASE_URL` - CLOB API base URL.
- `BERTBOT_POLYMARKET_DATA_BASE_URL` - Data API base URL.

Webhook server and connector variables:

- `BERTBOT_WEBHOOK_HOST` - bind host for the webhook server. Default: `0.0.0.0`.
- `BERTBOT_WEBHOOK_PORT` - bind port for the webhook server. Default: `8088`.
- `BERTBOT_WEBHOOK_TELEGRAM_PATH` - Telegram webhook path. Default: `/webhook/telegram`.
- `BERTBOT_WEBHOOK_SLACK_PATH` - Slack webhook path. Default: `/webhook/slack`.
- `BERTBOT_WEBHOOK_WHATSAPP_PATH` - WhatsApp webhook path. Default: `/webhook/whatsapp`.
- `BERTBOT_WEBHOOK_HEALTH_PATH` - health endpoint path. Default: `/health`.
- `BERTBOT_WEBHOOK_DRY_RUN` - when true, process payloads without persistence writes.
- `BERTBOT_WEBHOOK_REQUIRE_SIGNATURES` - when true, enforce provider request verification checks.
- `BERTBOT_WEBHOOK_TRUST_PROXY_HEADERS` - when true, trust `X-Forwarded-For` / `X-Real-IP` as client IP source.
- `BERTBOT_WEBHOOK_ALLOWED_IPS` - comma-separated allowlist of client IPs and CIDRs.
- `BERTBOT_WEBHOOK_RATE_LIMIT_WINDOW_SECONDS` - rate-limit window size in seconds.
- `BERTBOT_WEBHOOK_RATE_LIMIT_MAX_REQUESTS` - max accepted requests per client IP per window.

Connector enablement and platform metadata:

- `BERTBOT_TELEGRAM_ENABLED` - enable Telegram adapter wiring. Default: `true` for webhook mode.
- `BERTBOT_SLACK_ENABLED` - enable Slack adapter wiring. Default: `true` for webhook mode.
- `BERTBOT_WHATSAPP_ENABLED` - enable WhatsApp adapter wiring. Default: `true` for webhook mode.
- `BERTBOT_SLACK_WORKSPACE_ID` - optional Slack workspace/team identifier used in normalized source metadata.
- `BERTBOT_WHATSAPP_BUSINESS_PHONE_ID` - optional WhatsApp Business phone number ID used in normalized source metadata.
- `BERTBOT_INGESTION_REQUIRE_APPROVAL` - require source approval before accepted message ingestion/chat state updates.

Provider verification variables (used when `BERTBOT_WEBHOOK_REQUIRE_SIGNATURES=true`):

- `BERTBOT_TELEGRAM_SECRET_TOKEN` - expected `X-Telegram-Bot-Api-Secret-Token` header value.
- `BERTBOT_SLACK_SIGNING_SECRET` - Slack app signing secret for HMAC verification.
- `BERTBOT_SLACK_MAX_REQUEST_AGE_SECONDS` - allowable Slack timestamp skew window. Default: `300`.
- `BERTBOT_WHATSAPP_APP_SECRET` - Meta app secret used to verify `X-Hub-Signature-256`.
- `BERTBOT_WHATSAPP_VERIFY_TOKEN` - token used for WhatsApp webhook subscription challenge verification.

Example local `.env` entry:

```bash
BERTBOT_AI_PROVIDER=openai
BERTBOT_AI_MODEL=gpt-4o-mini
BERTBOT_AI_API_KEY=your-api-key-here
```

If you are running the repo-local Copilot agent or the MCP server from VS Code, make sure the command is launched from the repository root so `bertbot-state.json`, `bertbot-memory.txt`, and `.env` resolve correctly.

The model field is kept in the config surface so the runtime can be pointed at different provider adapters without changing the command-line or manifest shape.

## Run Modes

### Interactive CLI

Use this mode when you want the original chat-style terminal loop:

```bash
.\gradlew.bat run --no-daemon
```

This mode accepts free-form input from standard input and uses the graph runtime plus memory store.

### Headless Prompt Mode

Use this mode when another process should submit a single prompt and read a single response:

```bash
.\gradlew.bat runHeadless --args="--prompt \"your request\"" --no-daemon
```

The headless entrypoint is the simplest option for scripted invocation and is the backend used by future automation layers.

### Local MCP Server

Use this mode when Copilot or another MCP client needs to call BertBot as a tool provider over stdio:

```bash
.\gradlew.bat runMcpServer --no-daemon
```

The server exposes core tools and should be launched from the repository root so the agent can locate local state files:

- `ask_bertbot` - pass a prompt to BertBot and get the orchestration response.
- `bertbot_status` - return runtime backend status for the active MCP session (provider/model/tool surface/timestamp).
- `workspace_list_dir` - list files and directories under a workspace-relative path.
- `workspace_read_file` - read a workspace-relative file.
- `workspace_search` - search workspace files for a text query.
- `polymarket_gamma_query` - query Polymarket Gamma API public endpoints.
- `polymarket_clob_query` - query Polymarket public CLOB market-data endpoints.
- `polymarket_data_query` - query Polymarket Data API public analytics endpoints.

When ingestion control is enabled, additional ingestion tools are also exposed:

- `ingestion_set_approval` - set source approval state for ingestion.
- `ingestion_list_approved_sources` - list approved ingestion sources.
- `ingestion_ingest_manual` - ingest a normalized message payload manually.
- `ingestion_chat_manual` - ingest a normalized message and return the assistant reply.

When `BERTBOT_MACROFACTOR_ENABLED=true` and MacroFactor credentials are configured, additional MacroFactor proxy tools are surfaced in `tools/list` with the configured prefix (default `macrofactor_`).

For workspace-managed startup in VS Code, this repository uses a stale-process-safe launcher at [scripts/mcp-stdio-launcher.ps1](scripts/mcp-stdio-launcher.ps1). It clears old workspace/task-matching processes before starting the selected Gradle MCP task.

A second launcher variant is available at [scripts/mcp-stdio-launcher-bertbot.ps1](scripts/mcp-stdio-launcher-bertbot.ps1). This wrapper pins the task to `runMcpServer` and is useful as a copy pattern for future backends that should each have their own task-specific wrapper.

Sample workspace MCP config for adding a second backend entry:

```json
{
	"servers": {
		"bertbot-backend": {
			"type": "stdio",
			"command": "powershell.exe",
			"args": [
				"-NoProfile",
				"-ExecutionPolicy",
				"Bypass",
				"-File",
				"${workspaceFolder}/scripts/mcp-stdio-launcher-bertbot.ps1"
			],
			"cwd": "${workspaceFolder}",
			"envFile": "${workspaceFolder}/.env"
		},
		"another-backend": {
			"type": "stdio",
			"command": "powershell.exe",
			"args": [
				"-NoProfile",
				"-ExecutionPolicy",
				"Bypass",
				"-File",
				"${workspaceFolder}/scripts/mcp-stdio-launcher.ps1",
				"runAnotherMcpServerTask"
			],
			"cwd": "${workspaceFolder}",
			"envFile": "${workspaceFolder}/.env"
		}
	}
}
```

The second entry is only a template. It requires a real Gradle task (`runAnotherMcpServerTask`) that starts a valid MCP stdio server.

### Webhook Server Mode

Use this mode when Telegram, Slack, or WhatsApp should POST webhook payloads directly to BertBot:

```bash
.\gradlew.bat runWebhookServer --no-daemon
```

Default local endpoints:

- `POST /webhook/telegram`
- `POST /webhook/slack`
- `POST /webhook/whatsapp`
- `GET /health`

WhatsApp verification challenge is supported on `GET /webhook/whatsapp` using the `hub.mode`, `hub.verify_token`, and `hub.challenge` query parameters.

If `BERTBOT_WEBHOOK_REQUIRE_SIGNATURES=true`, inbound requests are verified as follows:

- Telegram: `X-Telegram-Bot-Api-Secret-Token` must match `BERTBOT_TELEGRAM_SECRET_TOKEN`.
- Slack: `X-Slack-Request-Timestamp` + `X-Slack-Signature` must validate against `BERTBOT_SLACK_SIGNING_SECRET`.
- WhatsApp: `X-Hub-Signature-256` must validate against `BERTBOT_WHATSAPP_APP_SECRET`.

Additional hardening controls:

- IP allowlisting: set `BERTBOT_WEBHOOK_ALLOWED_IPS` (for example `10.0.0.0/8,192.168.1.10`).
- Rate limiting: tune `BERTBOT_WEBHOOK_RATE_LIMIT_WINDOW_SECONDS` and `BERTBOT_WEBHOOK_RATE_LIMIT_MAX_REQUESTS`.
- Reverse-proxy header trust: only enable `BERTBOT_WEBHOOK_TRUST_PROXY_HEADERS=true` when requests arrive through a trusted proxy that sanitizes forwarded headers.

### Tracing And Graph Visualization

BertBot emits structured tracing for graph execution and delegation lifecycles, including events like node start/completion, edge transitions, delegation requested/started/completed, and skill invocation.

The generated `bertbot-trace.jsonl` file can be tailed or filtered by `traceId` for debugging.

For a quick graphical view of interactions, open `bertbot-interactions.mmd` in VS Code Markdown preview (or Mermaid-compatible viewers). The file is refreshed after each request and renders a time-ordered sequence of delegation and node transitions.

### Copilot Custom Agent

The repository-local agent is defined in [.github/agents/bertbot.agent.md](.github/agents/bertbot.agent.md) and points at the `bertbot-backend` MCP server.

The MCP server registration is workspace-local in [.vscode/mcp.json](.vscode/mcp.json). This is what lets VS Code discover and start `bertbot-backend`.

To use it in VS Code:

1. Open Copilot Chat in the repository.
2. Make sure the provider variables are available in the environment or `.env` file.
3. Run `MCP: List Servers` and confirm `bertbot-backend` is enabled/trusted and started.
4. Select `@BertBot` from the agent picker, or invoke it directly from chat if your Copilot setup exposes custom agents.

If `bertbot-backend` tools do not appear in chat:

1. Run `MCP: Open Workspace Folder MCP Configuration` and verify `.vscode/mcp.json` is loaded for this workspace.
2. Run `MCP: Reset Cached Tools`, then `MCP: List Servers` and restart `bertbot-backend`.
3. Run `MCP: Reset Trust` if server trust was previously denied.
4. Reload the VS Code window after MCP configuration changes.

Quick self-check: call `bertbot-backend/bertbot_status` from chat tools. If the call succeeds, your chat session is actively routed through the workspace MCP backend.

### Scenario Summary

- Use `run` for human-in-the-loop interactive work.
- Use `runHeadless` for one-shot scripted requests.
- Use `runMcpServer` for manual MCP backend startup outside VS Code server management, or rely on `.vscode/mcp.json` for workspace-managed startup.
- Use `runWebhookServer` for direct Telegram/Slack/WhatsApp webhook ingress with optional provider signature verification.
- Use the repo-local agent manifest when you want Copilot to route work to BertBot automatically.
- Use `BERTBOT_AI_PROVIDER` and `BERTBOT_AI_MODEL` when you want to change the LLM adapter settings without changing code.

## Self-Hosted Docker Compose

This repository now includes a Day 1 self-hosted container baseline:

- `Dockerfile` - multi-stage build and runtime image.
- `docker-compose.yml` - app + PostgreSQL + optional Ollama services.
- `docker/entrypoint.sh` - runtime mode switch (`webhook`, `mcp`, `headless`, `interactive`).
- `.env.compose.example` - compose-specific runtime settings template.

### Quick Start

1. Copy `.env.compose.example` to `.env.compose` and set your API key.
2. Start the compose stack:

```bash
docker compose up --build -d
```

3. Check logs:

```bash
docker compose logs -f bertbot
```

4. Verify health endpoint:

```bash
curl http://localhost:8088/health
```

5. Stop the stack:

```bash
docker compose down
```

### Optional Ollama Service

The compose file includes an optional Ollama container profile:

```bash
docker compose --profile ollama up --build -d
```

When `BERTBOT_AI_PROVIDER=ollama`, set `BERTBOT_AI_MODEL` to a model present in your Ollama instance (for example `llama3.1`).

### Compose Runtime Defaults

- App listens on `0.0.0.0:8088` inside the container.
- Host port mapping: `8088:8088`.
- Container startup mode defaults to `BERTBOT_RUN_MODE=webhook`.
- Persistence backend is configured to PostgreSQL in compose:
	- `BERTBOT_STATE_STORE=postgres`
	- `BERTBOT_STATE_JDBC_URL=jdbc:postgresql://postgres:5432/bertbot`

### Switching Runtime Modes In Container

Use `BERTBOT_RUN_MODE` in compose env or overrides:

- `webhook` (default)
- `mcp`
- `headless`
- `interactive`

## Build And Test

This project targets Java 17. If `.\gradlew.bat test --no-daemon` fails with `JAVA_HOME is set to an invalid directory`, point `JAVA_HOME` at a valid JDK 17 installation on your machine and make sure the path exists.

On Windows, a typical fix is to update `JAVA_HOME` to something like `C:\Program Files\Eclipse Adoptium\jdk-17.x.x.x-hotspot` and then open a new terminal before rerunning Gradle.

Run the test suite with:

```bash
.\gradlew.bat test --no-daemon
```

Run opt-in live MacroFactor proxy integration tests with explicit environment flags:

```bash
$env:BERTBOT_MACROFACTOR_LIVE_TEST="true"
$env:BERTBOT_MACROFACTOR_USERNAME="you@example.com"
$env:BERTBOT_MACROFACTOR_PASSWORD="your-password"
# Optional: enable live tools/call coverage.
$env:BERTBOT_MACROFACTOR_LIVE_TOOL="get_nutrition"
$env:BERTBOT_MACROFACTOR_LIVE_ARGS_JSON='{"day":"2026-07-11"}'
# Optional: enforce a stable tools/list contract assertion for a known upstream tool.
$env:BERTBOT_MACROFACTOR_EXPECTED_TOOL="get_nutrition"
# Optional: assert a known schema argument key exists for that expected tool.
$env:BERTBOT_MACROFACTOR_EXPECTED_ARG="day"
.\gradlew.bat test --tests "*MacrofactorToolRouterLiveIntegrationTest" --no-daemon
```

Run static analysis and formatting checks with:

```bash
.\gradlew.bat detekt ktlintCheck --no-daemon
```

Auto-format Kotlin sources with:

```bash
.\gradlew.bat ktlintFormat --no-daemon
```

## CI/CD

This repository includes GitHub Actions workflows:

- CI workflow: `.github/workflows/ci.yml`
	- Triggers on pushes to `main` (excluding tags), pull requests into `main`, and manual dispatch.
	- Validates the Gradle wrapper, runs the full `check` quality gate, and uploads reports as workflow artifacts.

- Actionlint workflow: `.github/workflows/actionlint.yml`
	- Triggers on workflow file changes in pushes and pull requests targeting `main`, and manual dispatch.
	- Lints GitHub Actions workflow syntax and configuration.

- Dependency review workflow: `.github/workflows/dependency-review.yml`
	- Triggers on pull requests into `main`.
	- Fails the pull request when newly introduced dependencies include vulnerable packages.

- CodeQL workflow: `.github/workflows/codeql.yml`
	- Triggers on pushes and pull requests for `main`, on a weekly schedule, and by manual dispatch.
	- Scans the Kotlin codebase for security and reliability issues using GitHub code scanning.

- CD workflow: `.github/workflows/cd.yml`
	- Triggers when pushing tags like `v1.0.0`.
	- Builds distribution archives and publishes a GitHub Release with artifacts from `build/distributions`.

- Auto PR workflow: `.github/workflows/auto-pr.yml`
	- Triggers on pushes to branches other than the repository default branch (and ignores `main`).
	- Opens a pull request into the repository default branch when one is not already open.

- Merge generated PRs workflow: `.github/workflows/merge-generated-prs-on-green.yml`
	- Triggers every 5 minutes on a schedule and via manual dispatch.
	- Applies guardrails for trusted generated PRs and `auto-merge`-labeled PRs, re-runs action-required checks, auto-approves when possible, and merges once all required checks pass.
	- Uses `AUTOMATION_PAT` when configured, with fallback to `github.token`.

- Secret scan workflow: `.github/workflows/secret-scan.yml`
	- Triggers on pushes, pull requests into `main`, and manual dispatch.
	- Scans commits for leaked secrets and uploads SARIF findings to GitHub code scanning.

## GitHub Copilot Automation

This repository also includes GitHub Copilot repository instructions in `.github/copilot-instructions.md` and a review-focused skill in `.github/skills/code-review/SKILL.md`.

The repository-local Copilot custom agent lives at `.github/agents/bertbot.agent.md` and uses the `bertbot-backend` MCP tool surface. The server itself is configured in `.vscode/mcp.json`.

To use GitHub-native review and implementation flow in pull requests:

- Enable GitHub Copilot code review for the repository.
- Enable the GitHub Copilot coding agent or cloud agent for the repository.
- Use **Fix with Copilot** from Copilot review comments when you want GitHub to implement a suggested improvement.

GitHub Copilot can suggest changes and draft implementations, but auto-applying arbitrary review feedback without an explicit pull request action remains a safety boundary in GitHub.

To trigger a release after publishing:

```bash
git tag v1.0.0
git push origin v1.0.0
```
