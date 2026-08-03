---
name: test-orchestration
description: |
  Use when you need to design, run, and interpret repository verification steps, including targeted tests, quality-gate slices, and failure triage.
license: Apache-2.0
metadata:
  version: v1
---

# Test Orchestration Skill

Use this skill when confidence in behavior matters more than speed of change.

## Best Fit

- Build and test verification after code or workflow changes.
- Narrow test selection for touched graph, config, persistence, or routing logic.
- Fast triage of failures into actionable fix paths.
- Repeatable validation scripts for local and CI parity.

## Working Rules

- Start with the narrowest meaningful verification for the modified surface.
- Expand to broader checks only when risk or failures justify it.
- Report what was executed and what was intentionally skipped.
- Keep failures mapped to concrete file or behavior impact.
- Prefer existing Gradle tasks and repository quality gates before adding new commands.

## Verification Sequence

1. Validate formatting and static checks for touched files when applicable.
2. Run focused tests for affected packages or classes.
3. Run broader `check` only when changes are cross-cutting or policy-sensitive.
4. Summarize confidence level, residual risk, and next verification step.

## Output Shape

Return verification as:
- Checks run.
- Outcome per check.
- Root cause summary for failures.
- Suggested next action.