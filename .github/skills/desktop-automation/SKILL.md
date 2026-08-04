---
name: desktop-automation
description: |
  Use when you need structured desktop and GUI automation through an MCP-backed desktop automation bridge, including application navigation, clicking, typing, and state verification.
license: Apache-2.0
metadata:
  version: v1
---

# Desktop Automation Skill

Use this skill when task success depends on real desktop interaction rather than static code analysis.

## Best Fit

- GUI-driven desktop workflows and application navigation.
- Click, type, and UI-state verification actions through a desktop automation bridge.
- Repetitive desktop procedures that can be executed safely with explicit checkpoints.
- Desktop automation where the target is a local application rather than a browser session.

## Working Rules

- Start with a short plan of target application, key steps, and success criteria.
- Prefer deterministic actions and explicit step checkpoints.
- Capture evidence from tool output after each critical action.
- Fail fast on missing targets rather than guessing next interactions.
- Keep automation idempotent where possible.

## Safety Constraints

- Do not submit irreversible actions without explicit user confirmation.
- Do not handle credentials in logs or generated artifacts.
- If a flow requires authentication secrets, request secure user-provided execution context.

## Output Shape

Return concise results with:
- Steps executed.
- Evidence captured.
- Success/failure at each checkpoint.
- Follow-up actions if the flow is blocked.
