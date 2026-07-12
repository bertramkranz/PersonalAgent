# Architecture

BertBot is organized around explicit runtime boundaries so orchestration, persistence, tooling, and transport concerns stay separable.

## Package Layout

- `com.personalagent.bertbot.app`: application entrypoints, runtime composition, tool routers, and transport wiring.
- `com.personalagent.bertbot.config`: persona, prompt, tool, and skill configuration.
- `com.personalagent.bertbot.llm`: provider-neutral LLM gateway contracts and adapters.
- `com.personalagent.bertbot.graph.model`: execution state model.
- `com.personalagent.bertbot.graph.nodes`: node implementations and identifiers.
- `com.personalagent.bertbot.graph.runtime`: graph contracts, edges, definitions, and runner logic.
- `com.personalagent.bertbot.graph.store`: snapshot persistence.
- `com.personalagent.bertbot.memory`: episodic, semantic, and profile memory handling.
- `com.personalagent.bertbot.ingestion`: external source approval and connector adapters.
- `com.personalagent.bertbot.agents`: delegated sub-agent registry and capability matching.

This split keeps graph contracts separate from runtime wiring and keeps persistence distinct from orchestration decisions.

## Runtime Flow

At a high level, BertBot:

1. Captures an inbound message.
2. Plans work and priorities.
3. Selects a sub-agent when delegation is appropriate.
4. Executes the delegated or direct workflow.
5. Persists the latest execution snapshot and supporting artifacts.

That design makes the path through the system easy to inspect in traces and interaction diagrams.

## Persistence Model

Local development defaults write to `state/` and `logs/`.

- State snapshots capture the latest execution result.
- Episodic and semantic memory retain learned context across runs.
- Profile data retains structured personal facts.
- Trace logs and Mermaid output make orchestration behavior inspectable.

Deployed environments can switch the same persistence surfaces to JDBC or PostgreSQL backends through configuration.

## Extension Points

To add new behavior safely:

- Add graph behavior in `graph.nodes` and register it through runtime composition.
- Keep tool families isolated in focused routers rather than expanding a single large dispatcher.
- Preserve the package split across `graph.model`, `graph.nodes`, `graph.runtime`, and `graph.store`.
- Add external control through configuration only when the runtime needs it.

## Diagrams

- [architecture.mmd](architecture.mmd) is the diagram source for the main architecture view.
- [cicd-diagram.mmd](cicd-diagram.mmd) documents the repository automation flow.