---
name: prompt-policy-regression
description: |
  Use when changing agent manifests, Copilot instructions, or prompt-construction code
  that controls orchestration policy, tool-calling behavior, and user-facing output style.
  Focus on preventing instruction drift and behavioral regressions.
license: Apache-2.0
metadata:
  version: v1
---

# Prompt And Policy Regression

Use this skill when prompt or instruction updates could silently alter behavior.

## Best Fit

- Edits to `.github/agents/*.agent.md`, `.github/copilot-instructions.md`, and prompt docs.
- Changes to runtime prompt assembly paths and policy text.
- Updates that alter tool-use constraints or output-format policy.
- Delegation contract and orchestration guideline changes.

## Working Rules

- Keep global policy centralized; avoid contradictory duplicates across files.
- Preserve plain-language user output defaults unless explicitly changed.
- Ensure tool-routing instructions remain actionable and testable.
- Validate that custom prompt overrides still inherit required safety and orchestration policy.
- Prefer incremental edits with targeted prompt tests.

## Regression Checklist

1. Diff old vs new policy statements for semantic drift.
2. Verify alignment between docs, agent manifests, and runtime prompt assembly.
3. Run prompt-related tests and update assertions when policy intentionally changes.
4. Report behavior changes explicitly in reviewer-facing notes.

## Output Shape

Return:
- Intended policy change.
- Detected drift or conflicts.
- Required test updates.
- Final behavior summary in user-facing terms.
