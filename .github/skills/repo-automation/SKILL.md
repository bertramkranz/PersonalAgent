---
name: repo-automation
description: |
  Use when you need to plan, execute, and verify multi-step repository automation tasks, including browser-assisted workflows, bulk file changes, project setup, maintenance scripts, and end-to-end verification.
license: Apache-2.0
metadata:
  version: v1
---

# Repository Automation Skill

Use this skill for tasks that need more than a one-off code change.

## Best Fit

- Multi-file repository updates.
- Browser-assisted workflows and repeatable UI actions.
- Project bootstrap, refactors, and maintenance scripts.
- Verification passes that combine search, edit, build, and test steps.

## Working Rules

- Start by gathering a small amount of evidence from the relevant files, configs, or runtime output.
- Prefer the smallest change that proves the intended behavior.
- Keep automation steps explicit so they can be replayed or audited.
- Use existing repository commands and conventions before inventing a new path.
- Validate changes with the narrowest useful check first, then expand only if needed.

## When To Escalate

- If the task touches workflows, persistence, or orchestration boundaries, verify the affected path with tests or targeted checks.
- If browser automation is required, prefer a repeatable Playwright flow over ad hoc clicking.
- If the task becomes a repeated maintenance pattern, split it into a focused sub-agent or dedicated script instead of growing this skill indefinitely.

## Output Shape

Return a concise action plan, the files or steps changed, and the verification performed.