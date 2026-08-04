---
name: workflow-guardrails-maintainer
description: |
  Use when editing GitHub Actions workflows, policy guardrails, required-check wiring,
  or CI enforcement scripts in PersonalAgent. Focus on preserving protection guarantees
  while making small, auditable workflow changes.
license: Apache-2.0
metadata:
  version: v1
---

# Workflow Guardrails Maintainer

Use this skill for workflow and CI policy changes where branch-protection integrity is critical.

## Best Fit

- Changes to `.github/workflows/*.yml`.
- Updates to DoD/path-coupled enforcement behavior.
- Required-check naming, branch-protection context, and auto-merge gating changes.
- Workflow script edits under `.github/scripts/` tied to CI policy.

## Working Rules

- Preserve required-check continuity unless migration steps are explicit.
- Prefer least-privilege workflow permissions.
- Keep policy checks deterministic and explainable.
- Avoid coupling unrelated policy concerns into a single workflow change.
- Validate workflow syntax and behavior assumptions before merge.

## Review Sequence

1. Identify impacted workflows, triggers, permissions, and required contexts.
2. Validate policy intent against repository docs and guardrail scripts.
3. Check for regressions in PR gating, auto-merge behavior, and doc-coupled rules.
4. Propose minimal fixes and verification steps.

## Output Shape

Return:
- Policy objective.
- Risk findings.
- Suggested patch-level changes.
- Verification checklist for CI and branch protection.
