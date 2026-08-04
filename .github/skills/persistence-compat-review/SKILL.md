---
name: persistence-compat-review
description: |
  Use when changes touch state snapshots, memory files, checkpoint stores, rollback,
  replay, or JDBC/file persistence behavior in PersonalAgent. Focus on compatibility,
  migration safety, and regression-proof verification.
license: Apache-2.0
metadata:
  version: v1
---

# Persistence Compatibility Review

Use this skill when persistence safety is a primary risk.

## Best Fit

- Changes to `graph.store`, state codecs, schema version handling, or snapshot envelopes.
- Edits affecting `bertbot-state.json`, `bertbot-memory.txt`, or scoped persistence files.
- Changes to checkpoint creation, rollback policies, or replay/event paths.
- File-backend and JDBC-backend behavior divergence checks.

## Working Rules

- Assume backward compatibility is required unless a migration plan is explicit.
- Verify read compatibility for legacy payloads before accepting new write formats.
- Keep file and JDBC behavior aligned for equivalent scenarios.
- Prefer minimal, test-backed fixes over broad store refactors.
- Call out persistence blast radius explicitly, including ops rollback impact.

## Verification Sequence

1. Identify touched persistence surfaces and schema contracts.
2. Check read/write compatibility assumptions against existing tests and fixtures.
3. Run focused store/runtime tests for changed components.
4. Expand to broader quality gate only when the change is cross-cutting.

## Output Shape

Return:
- Compatibility findings.
- Regression risks.
- Required test additions or updates.
- Confidence level and residual risk.
