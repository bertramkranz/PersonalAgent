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
- `com.personalagent.bertbot.agents` - sub-agent registry and capability matching.

## Runtime Flow

BertBot runs as a graph of named nodes:

1. Capture the incoming message.
2. Plan the work and identify priorities.
3. Match the task to a specialized sub-agent when appropriate.
4. Execute the delegated workflow.
5. Persist the resulting state to disk.

This structure keeps the orchestration logic easy to visualize and makes it straightforward to add more nodes later.

## Persistence

BertBot currently persists local state in these files:

- `bertbot-state.json` - graph execution state and delegation context.
- `bertbot-memory.txt` - episodic memory entries from recent interactions.
- `bertbot-semantic-memory.txt` - summarized semantic memory created from episodic history.
- `bertbot-profile.json` - structured profile facts (for example, remembered user name).
- `bertbot-trace.jsonl` - structured runtime trace events with trace IDs.
- `bertbot-interactions.mmd` - Mermaid sequence diagram generated from the latest trace and graph state.

These files are local to the workspace and help BertBot retain context across runs.

## Extending BertBot

To add a new capability:

- Add a new graph node in `com.personalagent.bertbot.graph.nodes`.
- Register the node in the graph definition in `com.personalagent.bertbot.app.BertBotApplication`.
- Add or update sub-agent definitions in `com.personalagent.bertbot.agents.SubAgentRegistry`.
- Refine the persona, tools, or skills in `com.personalagent.bertbot.config.BertBotAgentConfig`.

Default sub-agent roles currently include: Coder Agent, Planner Agent, Architect Agent, Analyst Agent, Copywriter Agent, Red Team Agent, Philosopher Agent, and Psychologist Agent.

## Configuration

BertBot reads configuration from the process environment first and falls back to a local `.env` file in the workspace root.

If you are setting up the project from scratch, copy [.env.example](.env.example) to `.env` and fill in your values.

The runtime is provider-aware at the LLM adapter boundary. The repository currently ships the OpenAI adapter, and new providers can be added by implementing the `LlmGateway` contract.

The current configuration variables are:

- `BERTBOT_AI_PROVIDER` - selects the active AI provider adapter. The shipped value is `openai`.
- `BERTBOT_AI_MODEL` - selects the chat model for the active provider adapter.
- `BERTBOT_AI_API_KEY` - provider-specific API key for the active adapter.

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

The server exposes two tools and should be launched from the repository root so the agent can locate local state files:

- `ask_bertbot` - pass a prompt to BertBot and get the orchestration response.
- `bertbot_status` - return runtime backend status for the active MCP session (provider/model/tool surface/timestamp).

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
- Use the repo-local agent manifest when you want Copilot to route work to BertBot automatically.
- Use `BERTBOT_AI_PROVIDER` and `BERTBOT_AI_MODEL` when you want to change the LLM adapter settings without changing code.

## Build And Test

This project targets Java 17. If `.\gradlew.bat test --no-daemon` fails with `JAVA_HOME is set to an invalid directory`, point `JAVA_HOME` at a valid JDK 17 installation on your machine and make sure the path exists.

On Windows, a typical fix is to update `JAVA_HOME` to something like `C:\Program Files\Eclipse Adoptium\jdk-17.x.x.x-hotspot` and then open a new terminal before rerunning Gradle.

Run the test suite with:

```bash
.\gradlew.bat test --no-daemon
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

- Auto-approve Copilot-reviewed PRs workflow: `.github/workflows/auto-approve-copilot-reviewed-prs.yml`
	- Triggers when `copilot-pull-request-reviewer[bot]` submits an approval review.
	- Adds an approval review via `hmarr/auto-approve-action` to streamline merge readiness.

- Merge generated PRs workflow: `.github/workflows/merge-generated-prs-on-green.yml`
	- Triggers on generated PR activity, selected upstream workflow completions, a 10-minute schedule, and manual dispatch.
	- Applies guardrails for trusted generated PRs, re-runs action-required checks, auto-approves when possible, and merges once all required checks pass.

- Copilot review workflow: `.github/workflows/copilot-review.yml`
	- Requests GitHub Copilot as a reviewer when a pull request is opened or updated.
	- Re-requests review on new pushes to the pull request branch.

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
