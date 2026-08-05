# VS Code And Copilot

BertBot can be used as a repo-local Copilot agent through an MCP backend.

Related docs: [configuration.md](configuration.md) for provider settings, [run-modes.md](run-modes.md) for the MCP runtime command, and [github-automation.md](github-automation.md) for GitHub-side Copilot review and automation details.

## Agent Definition

The repository-local agent manifests live at:

- [../.github/agents/bertbot.agent.md](../.github/agents/bertbot.agent.md) -> `@BertBot`
- [../.github/agents/repo-automation.agent.md](../.github/agents/repo-automation.agent.md) -> `@Repo Automation`
- [../.github/agents/test-orchestration.agent.md](../.github/agents/test-orchestration.agent.md) -> `@Test Orchestration`
- [../.github/agents/repo-ops.agent.md](../.github/agents/repo-ops.agent.md) -> `@Repo Ops`
- [../.github/agents/browser-automation.agent.md](../.github/agents/browser-automation.agent.md) -> `@Browser Automation`
- [../.github/agents/desktop-automation.agent.md](../.github/agents/desktop-automation.agent.md) -> `@Desktop Automation`

These agents are intended to operate against a `bertbot-backend` MCP server and related tool surfaces.

Use `@BertBot` for orchestration-heavy requests and cross-domain delegation.

Use `@Repo Automation` for direct multi-step implementation, verification, and browser-assisted repository workflows.

Use `@Test Orchestration` for verification-first workflows, targeted checks, and failure triage.

Use `@Repo Ops` for repeatable maintenance tasks like dependency hygiene, workflow upkeep, and repository health checks.

Use `@Browser Automation` for checkpointed web-browser workflows such as navigation, form completion, content extraction, and UI verification.

Use `@Desktop Automation` for desktop and GUI automation workflows through the desktop automation bridge.

## Agent Selection Matrix

| If your task is primarily... | Use this agent | Why |
| --- | --- | --- |
| Cross-domain planning, decomposition, or orchestration | `@BertBot` | Best default for routing complex objectives across capabilities. |
| Multi-step implementation and execution | `@Repo Automation` | Focused on evidence-backed edits and workflow execution. |
| Verification, focused checks, and failure triage | `@Test Orchestration` | Optimized for confidence-building validation and actionable test outcomes. |
| Recurring maintenance and operational hygiene | `@Repo Ops` | Tailored for dependency, workflow, and repository-health upkeep. |
| Browser-driven web workflows and web task execution | `@Browser Automation` | Specialized for Playwright-backed interaction flows with explicit checkpoints and web-only safety constraints. |
| Desktop and GUI automation workflows | `@Desktop Automation` | Specialized for MCP-backed desktop interaction flows with explicit checkpoints and desktop-focused safety constraints. |

When unsure, start with `@BertBot` and then switch to a specialized agent once the task shape is clear.

## Custom Skills

For multi-step repository maintenance or browser-assisted automation, use [../.github/skills/repo-automation/SKILL.md](../.github/skills/repo-automation/SKILL.md).

For focused build/test validation and check planning, use [../.github/skills/test-orchestration/SKILL.md](../.github/skills/test-orchestration/SKILL.md).

For recurring maintenance and operational hygiene automation, use [../.github/skills/repo-ops/SKILL.md](../.github/skills/repo-ops/SKILL.md).

For browser-driven workflows with explicit safety and checkpointing, use [../.github/skills/browser-automation/SKILL.md](../.github/skills/browser-automation/SKILL.md).

For desktop and GUI automation through the desktop automation bridge, use [../.github/skills/desktop-automation/SKILL.md](../.github/skills/desktop-automation/SKILL.md).

The existing repo-specific review baseline is [../.github/skills/code-review/SKILL.md](../.github/skills/code-review/SKILL.md).

For hosted release operations and startup recovery on Cloud Run, use [../.github/skills/cloudrun-release-ops/SKILL.md](../.github/skills/cloudrun-release-ops/SKILL.md).

For persistence and rollback compatibility reviews, use [../.github/skills/persistence-compat-review/SKILL.md](../.github/skills/persistence-compat-review/SKILL.md).

For MCP capability and tool-availability diagnosis, use [../.github/skills/mcp-capability-diagnostics/SKILL.md](../.github/skills/mcp-capability-diagnostics/SKILL.md).

For CI policy and required-check guardrail maintenance, use [../.github/skills/workflow-guardrails-maintainer/SKILL.md](../.github/skills/workflow-guardrails-maintainer/SKILL.md).

For prompt/instruction drift prevention and policy regression checks, use [../.github/skills/prompt-policy-regression/SKILL.md](../.github/skills/prompt-policy-regression/SKILL.md).

## Starting The MCP Backend

You can launch the backend directly with:

```bash
.\gradlew.bat runMcpServer --no-daemon
```

For workspace-managed PowerShell startup, use one of the provided launchers:

- [../scripts/mcp-stdio-launcher.ps1](../scripts/mcp-stdio-launcher.ps1)
- [../scripts/mcp-stdio-launcher-bertbot.ps1](../scripts/mcp-stdio-launcher-bertbot.ps1)

Launch from the repository root so `.env`, `state/`, and other workspace-relative paths resolve correctly.

## Workspace MCP Configuration

This repository documents a workspace-local MCP setup pattern, but no committed `.vscode/mcp.json` is present in the current tree.

If you use workspace-managed startup in VS Code, create that file locally and register a `bertbot-backend` stdio server that points at [../scripts/mcp-stdio-launcher-bertbot.ps1](../scripts/mcp-stdio-launcher-bertbot.ps1).

Typical shape:

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
		}
	}
}
```

## Using BertBot In VS Code

1. Open Copilot Chat in the repository.
2. Make sure provider variables are available through the terminal environment or `.env`.
3. Run `MCP: List Servers` and confirm `bertbot-backend` is enabled, trusted, and started.
4. Select `@BertBot` from the agent picker if your Copilot configuration exposes custom agents.

## Troubleshooting Missing Tools

If `bertbot-backend` tools do not appear:

1. Verify your local workspace MCP configuration is loaded.
2. Run `MCP: Reset Cached Tools` and restart `bertbot-backend`.
3. Run `MCP: Reset Trust` if server trust was denied earlier.
4. Reload the VS Code window after changing MCP configuration.

Quick self-check: call `bertbot-backend/bertbot_status` from chat tools. If it succeeds, the chat session is reaching the workspace MCP backend.
