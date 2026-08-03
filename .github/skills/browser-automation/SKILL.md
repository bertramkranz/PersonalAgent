---
name: browser-automation
description: |
  Use when you need structured browser-assisted automation for web tasks, including navigation, form flows, extraction, and repeatable UI interactions with explicit safety boundaries.
license: Apache-2.0
metadata:
  version: v1
---

# Browser Automation Skill

Use this skill when task success depends on real browser interaction rather than static code analysis.

## Best Fit

- Multi-step website workflows (login-free preferred).
- Form filling, navigation chains, and UI state verification.
- Data capture from web pages with source evidence.
- Repeatable browser procedures that can be rerun safely.

## Working Rules

- Start with a short plan of target URL, key steps, and success criteria.
- Prefer deterministic selectors and explicit step checkpoints.
- Capture evidence from page state after each critical action.
- Fail fast on missing elements rather than guessing next clicks.
- Keep automation idempotent where possible.

## Safety Constraints

- Do not submit irreversible actions (purchase, delete, account changes) without explicit user confirmation.
- Do not handle credentials in logs or generated artifacts.
- If a flow requires authentication secrets, request secure user-provided execution context.

## Output Shape

Return concise results with:
- Steps executed.
- Evidence captured.
- Success/failure at each checkpoint.
- Follow-up actions if the flow is blocked.