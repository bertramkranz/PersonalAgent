# Koog Beta Dependency Upgrade Policy

BertBot depends on one Koog beta module alongside stable Koog releases.  This document defines the pinning strategy, criteria for bumping the beta version, the upgrade checklist, and rollback steps.

## Current Beta Module

| Module | Version variable | Declared version |
| --- | --- | --- |
| `ai.koog:agents-features-longterm-memory` | `koogBetaVersion` in `build.gradle.kts` | `1.0.0-beta` |

Stable Koog modules (`koog-agents`, `agents-features-memory`, `agents-features-opentelemetry`) are tracked separately under `koogVersion` and follow normal semver upgrade rules.

## Pinning Policy

- Both `koogVersion` and `koogBetaVersion` in `build.gradle.kts` must always be declared as **exact version strings**.  The beta module is pinned via `koogBetaVersion`, and floating ranges (`+`, `latest.release`, `beta+`) are not used.
- Both variables must be reviewed together whenever either is bumped, so the stable and beta module sets stay compatible.
- Dependency updates are reviewed on a **monthly cadence** unless a blocking bug or security advisory forces an earlier upgrade.

## Criteria for Bumping the Beta Version

Upgrade the beta version when **all** of the following are true:

1. The new version is published to Maven Central and the release notes or changelog are available.
2. No open breaking-change issues are recorded against the new version in the Koog issue tracker.
3. The upgrade checklist below is run in full and all steps pass.
4. The change is made in an isolated pull request so the diff remains reviewable.

Do not bump the beta version as part of a PR that also changes production logic.

## Upgrade Checklist

Run all steps in order.  Do not merge until every step passes.

### 1. Update the version

Edit `koogBetaVersion` in `build.gradle.kts`:

```kotlin
val koogBetaVersion = "<new-version>"
```

### 2. Verify the dependency resolves

```bash
./gradlew --no-daemon dependencies --configuration runtimeClasspath | grep koog
```

Confirm the expected version appears and no eviction conflicts are printed.

### 3. Run the full quality gate

```bash
./gradlew --no-daemon check
```

This runs `detekt`, `ktlintCheck`, and all unit tests with JaCoCo coverage.  All tasks must pass.

### 4. Run a headless smoke check

Start BertBot in headless mode and confirm it initialises without errors:

```bash
./gradlew --no-daemon runHeadless
```

Exit immediately once the startup banner appears.  Confirm that no `ClassNotFoundException`, `NoSuchMethodError`, or similar binary-incompatibility signals appear.

### 5. Review the diff

Confirm the only change in `build.gradle.kts` is the `koogBetaVersion` value.  If the new release also changes the stable API surface used by `KoogRuntimeIntegration.kt` or `BertBotSupport.kt`, update those files in a separate follow-up PR and satisfy the CI-native DoD/path-coupled rules described in [github-automation.md](github-automation.md#ci-native-dod-enforcement).

### 6. Record the upgrade

Add an entry to the table at the bottom of this file under [Upgrade History](#upgrade-history).

## Rollback Steps

If a beta upgrade causes a regression:

1. Revert `koogBetaVersion` to the previous pinned value.
2. Run `./gradlew --no-daemon check` to confirm the revert is clean.
3. Record the failed version and reason in the [Upgrade History](#upgrade-history) table with a `reverted` status.
4. Open an issue in the Koog tracker if the regression appears to be a library defect.

## Upgrade History

| Date | Previous version | New version | Status | Notes |
| --- | --- | --- | --- | --- |
| — | — | `1.0.0-beta` | current | Initial pin at project creation |
