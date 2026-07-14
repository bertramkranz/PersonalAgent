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

## Shopping Workflow Stages

BertBot supports a structured shopping assistance workflow. Each stage corresponds to a user intent that routes through the same graph pipeline:

- **onboarding**: User sets up shopping preferences and profile constraints (budget, preferred sellers, categories).
- **recommendation**: Agent suggests products based on profile, constraints, and current context.
- **compare**: Agent produces a structured comparison of candidate products.
- **cart_prepare**: Agent prepares a proposed cart for user review. Requires explicit confirmation before any state change.
- **checkout_prepare**: Agent prepares a checkout summary for user review. Never autonomously finalises a purchase.

Safety invariants apply to all shopping stages:

- State-changing actions (`cart_prepare`, `checkout_prepare`) always require explicit user confirmation.
- Budget and seller-threshold checks run before any cart or checkout preparation.
- Final checkout is never performed autonomously.

When a direct MCP shopping integration is unavailable, BertBot can advertise Playwright browser automation as a fallback capability through the coder sub-agent. This fallback is optional and must be explicitly enabled in sub-agent configuration. The `RuntimeCapabilitySnapshot` reports both the Playwright sub-agent advertisement and the direct Playwright fallback availability so the system prompt stays accurate.

## Persistence Model

Local development defaults write to `state/` and `logs/`.

- State snapshots capture the latest execution result.
- Episodic and semantic memory retain learned context across runs.
- Profile data retains structured personal facts.
- Trace logs and Mermaid output make orchestration behavior inspectable.

Deployed environments can switch the same persistence surfaces to JDBC or PostgreSQL backends through configuration. The active backend is included in the `RuntimeCapabilitySnapshot` and reported by the `bertbot_status` MCP tool and the capability status response so both operators and the LLM know which store is in use.

## Configurable Stores

Every persistence surface (state, checkpoints, episodic memory, semantic memory, profile, ingestion consent, ingestion source state, research recommendations) supports two backends selectable via `BERTBOT_STATE_STORE`:

- `file` — local file-backed JSON storage, suitable for development and single-node deployments.
- `jdbc` / `postgres` / `postgresql` — relational backend for multi-instance or cloud deployments.

See [configuration.md](configuration.md) for variable names and [deployment.md](deployment.md) for container and Cloud Run wiring.

## Extension Points

To add new behavior safely:

- Add graph behavior in `graph.nodes` and register it through runtime composition.
- Keep tool families isolated in focused routers rather than expanding a single large dispatcher.
- Preserve the package split across `graph.model`, `graph.nodes`, `graph.runtime`, and `graph.store`.
- Add external control through configuration only when the runtime needs it.

## Diagrams

- [architecture.mmd](architecture.mmd) is the diagram source for the main architecture view.
- [cicd-diagram.mmd](cicd-diagram.mmd) documents the repository automation flow.
