# BertBot

BertBot is a graph-based personal assistant orchestration agent built to stay composable, inspectable, and easy to extend across local CLI, MCP, webhook, and Discord runtimes.

## What BertBot Is

- Graph-driven orchestration with explicit nodes, edges, and persisted execution snapshots.
- Multi-surface runtime support for interactive CLI, one-shot headless prompts, MCP tooling, webhooks, and Discord.
- Local-first development defaults with optional JDBC or PostgreSQL persistence for deployed environments.

## Quick Start

### Prerequisites

- Java 17
- A configured LLM provider such as OpenAI or Ollama
- PowerShell on Windows for the provided scripts and Gradle commands

### Environment Setup

1. Copy [.env.example](.env.example) to `.env`.
2. Set at least:

```bash
BERTBOT_AI_PROVIDER=openai
BERTBOT_AI_MODEL=gpt-4o-mini
BERTBOT_AI_API_KEY=your-api-key-here
```

### Run Locally

Interactive CLI:

```bash
.\gradlew.bat run --no-daemon
```

One-shot prompt:

```bash
.\gradlew.bat runHeadless --args="--prompt \"your request\"" --no-daemon
```

Local MCP server:

```bash
.\gradlew.bat runMcpServer --no-daemon
```

### Verify It Works

```bash
.\gradlew.bat test --no-daemon
```

If `JAVA_HOME` is invalid, point it at a JDK 17 installation and open a new terminal before rerunning Gradle.

## Which Doc Do I Need?

- Setup or env vars: [docs/configuration.md](docs/configuration.md)
- How to run BertBot locally or via MCP/webhooks: [docs/run-modes.md](docs/run-modes.md)
- Docker Compose or Cloud Run deployment: [docs/deployment.md](docs/deployment.md)
- VS Code and custom Copilot agent setup: [docs/vscode-copilot.md](docs/vscode-copilot.md)
- Architecture and extension points: [docs/architecture.md](docs/architecture.md)
- Repository automation and Copilot review workflow: [docs/github-automation.md](docs/github-automation.md)

Start with [docs/index.md](docs/index.md) for the full docs index.

## Common Commands

- `run` for interactive terminal usage.
- `runHeadless` for a single prompt/response flow.
- `runMcpServer` for MCP clients such as VS Code Copilot.
- `runWebhookServer` for Telegram, Slack, or WhatsApp webhook ingress.
- `runDiscordBot` for Discord gateway-based two-way messaging.
- `test` or `check` for validation.

## Project Structure

The main package boundaries are:

- `com.personalagent.bertbot.app` for entrypoints and runtime wiring.
- `com.personalagent.bertbot.config` for persona, prompt, tool, and skill configuration.
- `com.personalagent.bertbot.llm` for LLM gateway contracts and provider adapters.
- `com.personalagent.bertbot.graph.model` for graph state.
- `com.personalagent.bertbot.graph.nodes` for node implementations and identifiers.
- `com.personalagent.bertbot.graph.runtime` for edges, definitions, runner logic, and execution contracts.
- `com.personalagent.bertbot.graph.store` for state persistence.
- `com.personalagent.bertbot.memory` for episodic, semantic, and profile memory.
- `com.personalagent.bertbot.ingestion` for external chat source approval and connectors.
- `com.personalagent.bertbot.agents` for sub-agent capability matching.

See [docs/architecture.md](docs/architecture.md) for the narrative architecture guide and [docs/architecture.mmd](docs/architecture.mmd) for the diagram source.

## Persistence

Local development defaults write state into `state/` and runtime traces into `logs/`.

- `state/bertbot-state.json` stores the latest execution snapshot.
- `state/bertbot-memory.txt` and `state/bertbot-semantic-memory.txt` store episodic and summarized memory.
- `state/bertbot-profile.json` stores structured profile facts.
- `logs/bertbot-trace.jsonl` stores structured trace events.
- `state/bertbot-interactions.mmd` stores a Mermaid interaction view of the latest run.

Containerized and hosted deployments are expected to use PostgreSQL-backed persistence. See [docs/deployment.md](docs/deployment.md).

## Build And Test

Run the full quality gate with:

```bash
.\gradlew.bat check --no-daemon
```

For the narrower test suite only:

```bash
.\gradlew.bat test --no-daemon
```

## Extending BertBot

To add a new capability:

1. Add or update a graph node in `com.personalagent.bertbot.graph.nodes`.
2. Register it in the graph definition and runtime wiring.
3. Update sub-agent selection or tool routing if the capability is delegated.
4. Extend configuration only where the new behavior needs external control.

For architecture and runtime placement guidance, use [docs/architecture.md](docs/architecture.md).
