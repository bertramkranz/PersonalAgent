# BertBot Power-User Handbook

This guide is meant to help you get the most value from BertBot in everyday work, especially when you care about self-learning, memory quality, and reliable MCP usage.

The root [README.md](../README.md) is still the right place for a quick start. This document is meant to be the practical handbook for using the agent well.

## 1. How to think about BertBot

BertBot is most effective when you treat it as a structured collaborator rather than a one-shot chat bot.

It is strongest for:

- decomposing messy tasks into clear steps
- working across files, code, state, and tools
- using memory and session history to improve future interactions
- verifying conclusions with evidence
- orchestrating multi-step work through MCP or chat transports

It is less reliable when you ask for vague, open-ended execution without scope or constraints.

## 2. The best working pattern

The most reliable pattern is:

1. Plan
2. Execute
3. Verify
4. Summarize

That four-step loop leads to much better outcomes than asking for a direct answer immediately.

### Good prompt patterns

Use prompts that make the workflow explicit:

- “Inspect the repository first, then propose the smallest safe next step.”
- “Plan the task, execute it, verify the result, and then summarize the evidence.”
- “Do not assume anything; check the relevant files before you conclude.”
- “Show your reasoning briefly and keep the final answer concise.”

### Stronger prompts include constraints

Examples:

- “Use the smallest possible change.”
- “Prefer evidence over intuition.”
- “Keep the answer focused on practical next steps.”
- “If there is uncertainty, say so explicitly.”

## 3. Advanced prompting techniques

### Ask for a plan before execution

For anything non-trivial, ask for a short plan first.

Example:

- “Give me a short implementation plan, then ask for permission before you make changes.”

This reduces mistakes and makes the agent easier to supervise.

### Ask for evidence, not just conclusions

A very effective style is:

- “Verify your answer against the relevant files or tool output.”
- “Show the evidence you used before you finalize your conclusion.”

### Separate intent from execution

You can make the agent more useful by separating the request into two layers:

- what you want it to achieve
- how strict it should be about verification

Example:

- “Achieve the goal, but do not claim success until you have verified it.”

## 4. Self-learning and memory hygiene

BertBot’s learning features are powerful, but they work best when you guide them deliberately.

### Use memory intentionally

When you want the agent to remember something, state it clearly and concretely.

Good examples:

- “Remember that I prefer concise answers.”
- “Remember that I prefer evidence-backed conclusions.”
- “Remember that I like short implementation plans with concrete next steps.”

### Keep preferences stable and specific

Strong preferences are better than vague ones.

Prefer:

- “I want short summaries with bullet points.”

Over:

- “Be helpful.”

### Review learning changes before relying on them

The best practice is to review memory and session history regularly rather than assuming it learned the right thing.

Useful habits:

- inspect session history after a long conversation
- review pending learning approvals if they are enabled
- check state and memory outputs when you want to understand what changed

### A simple rule of thumb

If the preference matters a lot, say it clearly and repeat it when needed. If it is temporary, keep it temporary.

## 5. Best practices in VS Code and MCP mode

If you want the strongest day-to-day workflow, use BertBot through the MCP backend in VS Code.

### Recommended agent choice

Use the right agent for the task:

- @BertBot for orchestration and planning
- @Repo Automation for implementation and repo work
- @Test Orchestration for verification and failure triage
- @Browser Automation for web workflows
- @Desktop Automation for GUI workflows

### Recommended workflow

1. Start the MCP backend.
2. Connect it to the workspace.
3. Use a focused prompt with a clear objective.
4. Verify the result before you trust it.

### Useful MCP checks

If tools are missing or the backend feels unresponsive, check:

- whether the MCP backend is running
- whether the server is trusted in VS Code
- whether workspace tools were reset after a configuration change
- whether the workspace was launched from the repository root

See [vscode-copilot.md](vscode-copilot.md) for setup details.

## 6. Troubleshooting common issues

### The agent is too vague

Use a narrower prompt and ask for a plan first.

Example:

- “First outline the approach, then execute it step by step.”

### The agent seems to have learned the wrong thing

Review session history and memory-related outputs. If the memory is wrong, correct it explicitly and inspect whether the wrong learning needs to be rejected or revised.

### The agent is not using tools correctly

Ask it to:

- explain which tool it is using
- show the evidence it found
- stop and ask for clarification if the tool output is ambiguous

### Tools do not appear in VS Code

Check the MCP server connection, trust state, and workspace startup path.

## 7. Useful artifacts to inspect

BertBot writes artifacts that make it easier to understand what happened.

Useful ones include:

- state snapshots for execution context
- memory files for learned preferences and summaries
- trace logs for runtime details and tool behavior
- interaction diagrams for graph-level visibility

These are especially helpful when the agent is doing something complex or when you want to debug why it behaved a certain way.

## 8. Recommended modes by use case

- Use CLI mode for quick local exploration.
- Use MCP mode for richer tool use and VS Code integration.
- Use webhook mode for external chat integration.
- Use Discord mode when you want gateway-based chat interaction.

## 9. A practical daily workflow

A good daily loop looks like this:

1. Start with a clear objective.
2. Ask for a short plan.
3. Review the plan before execution.
4. Let the agent execute.
5. Ask it to verify results with evidence.
6. Review memory or session history if the task is important or long-running.

## 10. Starter prompts that work well

These prompts tend to produce strong outcomes:

- “Help me understand this repository and propose the smallest safe next step.”
- “Remember that I prefer evidence-backed recommendations.”
- “Review the last session and summarize what you learned about my preferences.”
- “Plan this task, execute it, verify the result, and then summarize the evidence.”
- “Use the available tools, but do not make assumptions without checking the relevant files.”

## 11. Bottom line

If you want the best experience from BertBot:

- use it as a structured collaborator
- pair it with MCP in VS Code
- guide its memory deliberately
- review what it learns before you trust it fully
- make verification a normal part of the workflow

That combination gives you the strongest balance of autonomy, transparency, and control.
