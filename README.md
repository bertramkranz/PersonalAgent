# BertBot

BertBot is a personal assistant orchestration agent built with a graph-based structure so it stays composable, inspectable, and easy to extend.

## Architecture

The project is organized around a small set of focused packages:

- `com.personalagent.bertbot.app` - application entrypoint and runtime wiring.
- `com.personalagent.bertbot.config` - BertBot persona, prompt, tool, and skill configuration.
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

BertBot currently persists two kinds of local state:

- `bertbot-state.json` - graph execution state and delegation context.
- `bertbot-memory.txt` - remembered personal context and preferences.

These files are local to the workspace and help BertBot retain context across runs.

## Extending BertBot

To add a new capability:

- Add a new graph node in `com.personalagent.bertbot.graph.nodes`.
- Register the node in the graph definition in `com.personalagent.bertbot.app.BertBotApplication`.
- Add or update sub-agent definitions in `com.personalagent.bertbot.agents.SubAgentRegistry`.
- Refine the persona, tools, or skills in `com.personalagent.bertbot.config.KoogAgentConfig`.

Default sub-agent roles currently include: Coder, Planner, Architect, Analyst, Copywriter, Red Teamer, Philosopher, and Psychologist.

## Build And Test

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

Run the app with:

```bash
.\gradlew.bat run --no-daemon
```

## CI/CD

This repository includes GitHub Actions workflows:

- CI workflow: `.github/workflows/ci.yml`
	- Triggers on pushes and pull requests to `main` or `master`.
	- Runs `detekt`, `ktlintCheck`, and `test`.

- CD workflow: `.github/workflows/cd.yml`
	- Triggers when pushing tags like `v1.0.0`.
	- Builds distribution archives and publishes a GitHub Release with artifacts from `build/distributions`.

To trigger a release after publishing:

```bash
git tag v1.0.0
git push origin v1.0.0
```
