---
name: code-review
description: |
  Review Kotlin, Gradle, workflow, and graph orchestration changes in PersonalAgent.
  Focus on correctness, persistence compatibility, test coverage, and small actionable fixes.
license: Apache-2.0
metadata:
  version: v1
---

# PersonalAgent Code Review

Use this skill when reviewing pull requests or generating implementation suggestions for this repository.

## Review Focus

- Check that graph node, edge, and registration changes stay aligned with the runtime flow.
- Flag persistence or serialization changes that could break `bertbot-state.json` or `bertbot-memory.txt`.
- Prefer minimal patch-ready suggestions, including tests, over abstract recommendations.
- Verify Gradle or workflow changes still support the repository quality gate: `./gradlew --no-daemon check`.
- Call out missing coverage when changes affect node routing, sub-agent registration, or prompt/config behavior.