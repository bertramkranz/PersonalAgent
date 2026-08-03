---
name: repo-ops
description: |
  Use when you need repeatable repository maintenance and operational automation, including dependency hygiene, workflow upkeep, environment consistency checks, and CI-oriented repository health tasks.
license: Apache-2.0
metadata:
  version: v1
---

# Repository Operations Skill

Use this skill for recurring repository maintenance work that should stay predictable and low-risk.

## Best Fit

- Dependency update planning and version hygiene checks.
- CI workflow upkeep and drift detection.
- Environment template parity and configuration consistency checks.
- Repository health maintenance tasks that repeat over time.

## Working Rules

- Start with read-only evidence before changing files.
- Prefer small maintenance increments over broad cleanup sweeps.
- Reuse existing scripts, workflows, and quality gates whenever possible.
- Keep operational changes auditable with explicit before/after notes.
- Avoid mixing feature development into maintenance-only runs.

## Standard Runbook

1. Identify the maintenance objective and impacted files.
2. Gather evidence from repository state, scripts, and workflow config.
3. Apply minimal edits needed to remove drift or improve reliability.
4. Run the narrowest verification relevant to the maintenance scope.
5. Report outcomes, residual risk, and next scheduled follow-up.

## Output Shape

Return concise plain-language results with:
- Maintenance objective.
- Changes applied.
- Verification performed.
- Follow-up recommendation.