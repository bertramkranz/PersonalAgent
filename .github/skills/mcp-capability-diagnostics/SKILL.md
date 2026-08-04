---
name: mcp-capability-diagnostics
description: |
  Use when MCP tools appear unavailable, partially registered, or behaviorally inconsistent.
  Focus on capability snapshot accuracy, tool routing diagnostics, startup races, and
  evidence-backed remediation.
license: Apache-2.0
metadata:
  version: v1
---

# MCP Capability Diagnostics

Use this skill when runtime capability and tool availability do not match operator expectations.

## Best Fit

- "Configured but unavailable" integration states.
- Missing tools in `tools/list` despite enabled config.
- Startup-time capability races for optional integrations.
- Disagreement between status surfaces and actual callable tools.

## Working Rules

- Treat runtime status and tool listing as ground truth, not static config intent.
- Distinguish `disabled`, `enabled but missing credentials`, `configured but unavailable`, and `enabled`.
- Prefer request-time capability checks for optional integrations.
- Keep diagnostics reproducible with explicit commands and expected outputs.
- Avoid assuming third-party MCP availability without direct evidence.

## Diagnostic Runbook

1. Capture MCP status and tool inventory evidence.
2. Compare configured integrations against advertised callable tools.
3. Isolate startup/config/auth causes and map each to a concrete fix path.
4. Re-check status after changes and report delta.

## Output Shape

Return:
- Observed capability state.
- Root-cause hypothesis with evidence.
- Fix steps ordered by probability and impact.
- Post-fix verification checklist.
