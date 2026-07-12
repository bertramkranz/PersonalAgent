# VS Code And Copilot

BertBot can be used as a repo-local Copilot agent through an MCP backend.

Related docs: [configuration.md](configuration.md) for provider settings, [run-modes.md](run-modes.md) for the MCP runtime command, and [github-automation.md](github-automation.md) for GitHub-side Copilot review and automation details.

## Agent Definition

The repository-local agent manifest lives at [../.github/agents/bertbot.agent.md](../.github/agents/bertbot.agent.md).

It is intended to operate against a `bertbot-backend` MCP server and related tool surfaces.

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
