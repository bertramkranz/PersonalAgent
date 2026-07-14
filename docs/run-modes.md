# Run Modes

BertBot supports multiple execution surfaces. Choose the mode based on who is driving the interaction and what kind of transport you need.

Related docs: [configuration.md](configuration.md) for required variables, [deployment.md](deployment.md) for container or Cloud Run deployment, and [vscode-copilot.md](vscode-copilot.md) for VS Code MCP setup.

## Quick Comparison

- `run`: interactive terminal chat loop for manual use.
- `runHeadless`: single prompt in, single response out.
- `runMcpServer`: stdio MCP tool provider for Copilot or other MCP clients.
- `runWebhookServer`: HTTP ingress for Telegram, Slack, and WhatsApp.
- `runDiscordBot`: Discord gateway-based two-way messaging.

## Interactive CLI

Use when you want the original terminal-driven chat workflow.

```bash
.\gradlew.bat run --no-daemon
```

This mode reads from standard input and uses the same graph runtime and memory stack as other local modes.

## Headless Prompt Mode

Use when another process should submit exactly one prompt and consume exactly one response.

```bash
.\gradlew.bat runHeadless --args="--prompt \"your request\"" --no-daemon
```

This is the simplest scripting surface and the cleanest backend for single-shot automation.

## Local MCP Server

Use when BertBot should expose tools over stdio to an MCP client.

```bash
.\gradlew.bat runMcpServer --no-daemon
```

Core tools include:

- `ask_bertbot`
- `bertbot_status`
- `workspace_list_dir`
- `workspace_read_file`
- `workspace_search`
- `polymarket_gamma_query`
- `polymarket_clob_query`
- `polymarket_data_query`

`workspace_list_dir`, `workspace_read_file`, and `workspace_search` now accept an optional `root` argument. Supported roots are `workspace` (default), `state`, and `logs`.

Example MCP calls:

```json
{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"workspace_read_file","arguments":{"root":"state","path":"bertbot-memory.txt"}}}
```

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"workspace_search","arguments":{"root":"logs","query":"ERROR","maxResults":10}}}
```

Additional tool groups are conditional:

- Ingestion tools appear when ingestion control is enabled.
- MacroFactor tools appear when `BERTBOT_MACROFACTOR_ENABLED=true` and credentials are configured.
- Google Workspace proxy tools appear when `BERTBOT_GOOGLE_WORKSPACE_ENABLED=true`.

The repository includes two PowerShell launchers:

- [../scripts/mcp-stdio-launcher.ps1](../scripts/mcp-stdio-launcher.ps1) for stale-process-safe Gradle MCP task startup.
- [../scripts/mcp-stdio-launcher-bertbot.ps1](../scripts/mcp-stdio-launcher-bertbot.ps1) for a task-specific BertBot MCP wrapper.

For Copilot and workspace-managed startup details, see [vscode-copilot.md](vscode-copilot.md).

## Webhook Server Mode

Use when external chat providers should POST payloads directly to BertBot.

```bash
.\gradlew.bat runWebhookServer --no-daemon
```

To retain a single local runtime log file:

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-webhook-with-log.ps1
```

Default endpoints:

- `POST /webhook/telegram`
- `POST /webhook/slack`
- `POST /webhook/whatsapp`
- `GET /health`

WhatsApp verification challenge handling is supported on `GET /webhook/whatsapp` using `hub.mode`, `hub.verify_token`, and `hub.challenge` query parameters.

When signature enforcement is enabled, requests are verified against provider-specific secrets. See [configuration.md](configuration.md).

For hosted webhook deployment guidance, see [deployment.md](deployment.md).

## Discord Bot Mode

Use when you want Discord messages and replies over gateway events instead of webhooks.

```bash
.\gradlew.bat runDiscordBot --no-daemon
```

Required settings:

- `BERTBOT_DISCORD_ENABLED=true`
- `BERTBOT_DISCORD_BOT_TOKEN=<token>`

Optional scope controls:

- `BERTBOT_DISCORD_GUILD_ID`
- `BERTBOT_DISCORD_APPROVED_CHANNEL_IDS`
- `BERTBOT_DISCORD_APPROVED_DIRECT_MESSAGE_IDS`

## Tracing And Graph Visualization

BertBot emits structured tracing for graph execution and delegation lifecycles.

- Trace events are written to `logs/bertbot-trace.jsonl` by default.
- A Mermaid interaction view is written to `state/bertbot-interactions.mmd` by default.

Use these artifacts to inspect node transitions, delegation, and runtime flow after a request completes.
