---
name: cloudrun-release-ops
description: |
  Use when you need safe, repeatable Cloud Run release operations for PersonalAgent,
  including deploy preflight checks, startup recovery triage, runtime health validation,
  and rollback-oriented incident handling.
license: Apache-2.0
metadata:
  version: v1
---

# Cloud Run Release Operations

Use this skill for hosted deployment and recovery work where release safety matters more than speed.

## Best Fit

- Cloud Run deploy readiness checks before changing runtime state.
- Startup failure triage after deploys.
- Verification that runtime env vars, secrets, and service account wiring match policy.
- Controlled rollback or fallback guidance when a release is unhealthy.

## Working Rules

- Start with read-only evidence from workflow config, deployment docs, and runtime status.
- Prefer deterministic checks over ad hoc debugging.
- Treat secret and service-account mappings as first-class release dependencies.
- Keep hosted port handling aligned to Cloud Run `PORT` contract unless explicitly overridden.
- If Cloud SQL is unavailable, verify fallback behavior and persistence implications before concluding incident scope.

## Standard Runbook

1. Confirm deploy trigger context (manual vs CI `workflow_run`) and target commit.
2. Validate required repo variables and optional secret references.
3. Verify Cloud SQL availability and expected state-store mode (`postgres` vs `file`).
4. Check deployed revision health and endpoint readiness.
5. Summarize whether to proceed, hold, or roll back with explicit evidence.

## Output Shape

Return concise plain-language results with:
- Release objective and target revision.
- Preflight findings.
- Health-check findings.
- Recommended next action (proceed, fix-forward, or rollback).
