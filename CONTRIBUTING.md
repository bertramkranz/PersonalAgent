# Contributing to BertBot

This guide explains how to set up a local environment, run the quality checks, and get a pull request merged.

## Local Setup

### Prerequisites

- Java 17 (Temurin/Adoptium recommended, set via `.sdkmanrc` or `JAVA_HOME`)
- A configured LLM provider such as OpenAI or Ollama
- On Windows, use the provided `gradlew.bat` wrapper from Command Prompt or PowerShell

### Environment

1. Copy [.env.example](.env.example) to `.env`.
2. Set at least the three required AI provider variables:

```bash
BERTBOT_AI_PROVIDER=openai
BERTBOT_AI_MODEL=gpt-5.6-luna
BERTBOT_AI_API_KEY=your-api-key-here
```

All other defaults in `.env.example` are intentionally conservative and safe to leave unchanged for local development. See [docs/configuration.md](docs/configuration.md) for the full variable reference.

## Build and Test

Run the full quality gate (compile, test, lint, static analysis, coverage):

```bash
./gradlew --no-daemon check
```

Run only tests:

```bash
./gradlew --no-daemon test
```

Generate a local JaCoCo HTML coverage report:

```bash
./gradlew --no-daemon jacocoTestReport
```

Reports are written to `build/reports/jacoco/`.

## Code Style and Static Analysis

Kotlin style is enforced by [ktlint](https://pinterest.github.io/ktlint/). Static analysis is enforced by [Detekt](https://detekt.dev/) with the project config at `config/detekt/detekt.yml`.

Both run as part of `check`. You can auto-fix ktlint violations before pushing:

```bash
./gradlew --no-daemon ktlintFormat
```

The CI `autofix-on-push.yml` workflow applies the same formatting automatically on feature branches.

## Architecture Boundaries

Keep the graph package split intact:

- `graph.model` — execution state model only
- `graph.nodes` — node implementations and identifiers
- `graph.runtime` — graph contracts, edges, definitions, and runner
- `graph.store` — snapshot persistence

Full package layout and extension guidance are in [docs/architecture.md](docs/architecture.md).

## CI Guardrails

This repository enforces two path-coupled doc requirements via `.github/scripts/changed-file-guardrails.sh`:

- **Architecture-significant changes** (`src/main/kotlin/...`, `build.gradle.kts`, `settings.gradle.kts`) require a corresponding update to `docs/architecture.mmd`.
- **Workflow file changes** (`.github/workflows/*.yml`) require a corresponding update to `docs/cicd-diagram.mmd`.

The `dod-enforcement.yml` workflow also checks that path-coupled test expectations are satisfied (for example, changes to graph store code must include matching graph store test updates).

CI runs `./gradlew --no-daemon check` as the final gate on all pull requests.

## Workflow Hygiene

When editing files under `.github/workflows/` or `.github/actions/`, pin every `uses:` action to a full commit SHA and keep the human-readable version comment.

Example:

```yaml
uses: actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5 # v4
```

Do not introduce tag-only references such as `@v4` without a SHA pin.

## PR Process

1. Branch off `main` with a descriptive name.
2. Make focused changes and keep the package boundaries above intact.
3. Run `./gradlew --no-daemon check` locally and ensure it passes before pushing.
4. Update `docs/architecture.mmd` or `docs/cicd-diagram.mmd` if your change triggers the guardrails above.
5. Open a pull request against `main`. CI will run the full quality gate automatically.
6. Address review feedback. The `autofix-on-push.yml` workflow will apply `ktlintFormat` automatically if style fixes are needed.

See [docs/github-automation.md](docs/github-automation.md) for details on the CI/CD guardrail and merge automation.
