When reviewing or changing code in this repository, prefer small, behavior-preserving changes over broad refactors.

Validate Kotlin, Gradle, or workflow changes with `./gradlew --no-daemon check` when the environment supports it.

Keep the graph architecture split consistent across `graph.model`, `graph.nodes`, `graph.runtime`, and `graph.store`.

Treat changes to `bertbot-state.json` and `bertbot-memory.txt` as compatibility-sensitive and call out migration or persistence risks in reviews.

For pull request reviews, prioritize correctness, state-flow regressions, serialization risks, missing tests, and configuration drift.

When possible, give concrete suggested changes or test additions instead of only high-level feedback.

Prefer existing Kotlin and Gradle patterns in the repository over introducing new dependencies.