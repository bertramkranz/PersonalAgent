# GitHub Automation

This repository includes GitHub workflow automation for merge guardrails, secret scanning, and Copilot-assisted review workflows.

## Workflow Overview

- `.github/workflows/bootstrap-repo-guardrails.yml`: ensures repository guardrails such as the `auto-merge` label exist.
- `.github/workflows/dod-enforcement.yml`: enforces Definition-of-Done style CI policy checks on pushes and pull requests, including path-coupled test expectations.
- `.github/workflows/autofix-on-push.yml`: applies safe formatting auto-fixes (`ktlintFormat`) on non-main branch pushes and commits them back automatically.
- `.github/workflows/merge-generated-prs-on-green.yml`: approves and enables GitHub native auto-merge for eligible pull requests when required checks pass.
- `.github/workflows/deploy-cloud-run-main.yml`: builds and deploys the webhook service to Cloud Run after CI succeeds on `main` (also supports manual dispatch).
- `.github/workflows/auto-version-tag.yml`: increments semantic patch tags on `main` and pushes the next `v*` tag.
- `.github/workflows/secret-scan.yml`: scans pushes and pull requests for leaked secrets and uploads SARIF findings.

## CI-Native DoD Enforcement

This repository favors CI-native policy checks over manual pull-request templates.

- `dod-enforcement.yml` runs on feature-branch pushes and pull requests.
- It validates path-coupled quality rules (for example, `graph/store` code changes must include matching `graph/store` test updates).
- It reuses the shared changed-file doc guardrail script to ensure architecture and CI/CD diagram updates are included when relevant.
- It runs `./gradlew --no-daemon check` as the final quality gate.

## Safe Auto-Fix Automation

`autofix-on-push.yml` is intentionally conservative:

- It only runs on non-main branch pushes.
- It applies deterministic formatting fixes (`ktlintFormat`) and auto-commits if diffs exist.
- It avoids looped reruns by skipping commits containing `[skip autofix]` and skipping bot-originated pushes.
- It does not attempt semantic or behavior-changing auto-fixes.

## Generated PR Merge Automation

The merge workflow is designed for trusted generated pull requests.

- It uses the `auto-merge` label as the main opt-in signal.
- It re-runs action-required checks when needed.
- It auto-approves when the configured automation identity is allowed to do so.
- It enables GitHub native auto-merge after required checks are green.
- It supports `AUTOMATION_PAT`, with fallback to `github.token` when applicable.

## Apply Updated Required Checks

After introducing new required workflows (for example `dod-enforcement`), re-apply branch protection contexts with the bootstrap workflow.

Using GitHub CLI:

```bash
gh workflow run bootstrap-repo-guardrails.yml --repo bertramkranz/PersonalAgent --field branch=main
```

Then verify branch protection required contexts include `dod-enforcement`:

```bash
gh api repos/bertramkranz/PersonalAgent/branches/main/protection
```

## Automation PAT Verification Runbook

- `github-actions[bot]` cannot own a Personal Access Token. Use a dedicated machine-user PAT or GitHub App token for `AUTOMATION_PAT`.
- Confirm the secret exists:

```bash
gh secret list --repo bertramkranz/PersonalAgent
```

- Confirm branch-protection checks and review policy:

```bash
gh api repos/bertramkranz/PersonalAgent/branches/main/protection
```

- Confirm automation-token identity from workflow logs:
  Run `Merge Generated PRs On Green` with `verify_token_identity=true` and check for `Automation token authenticates as '<login>'`.

- Validate approval and merge capability on a disposable pull request:
  Open a non-draft PR into `main`, ensure checks are green, then trigger the merge workflow manually.

## Secret Scanning

The secret-scan workflow:

- Runs on pushes
- Runs on pull requests into `main`
- Supports manual dispatch
- Uploads findings to GitHub code scanning

## Copilot Repository Guidance

This repository also includes:

- [../.github/copilot-instructions.md](../.github/copilot-instructions.md) for repository-level Copilot instructions
- [../.github/skills/code-review/SKILL.md](../.github/skills/code-review/SKILL.md) for review-oriented guidance in this codebase
- [vscode-copilot.md](vscode-copilot.md) for the repo-local BertBot custom agent and workspace MCP usage

## GitHub Copilot Review Flow

To use GitHub-native Copilot review and implementation flow in pull requests:

1. Enable GitHub Copilot code review for the repository.
2. Enable the GitHub Copilot coding agent or cloud agent for the repository.
3. Use `Fix with Copilot` from Copilot review comments when you want GitHub to implement a suggested improvement.

GitHub Copilot can suggest changes and draft implementations, but auto-applying arbitrary review feedback without an explicit pull-request action remains a safety boundary.

## Release Trigger

Releases are tag-driven through `.github/workflows/cd.yml`:

- `auto-version-tag.yml` runs on `main` and pushes the next `v*` tag.
- `auto-version-tag.yml` then dispatches `cd.yml` with `tag_ref` so release build/publish always runs for the new tag.
- `cd.yml` also supports direct `v*` tag push triggers and manual dispatch fallback.

Manual fallback:

```bash
git tag v1.0.0
git push origin v1.0.0
```

## Main Branch Deployment Trigger

Cloud deployment is now decoupled from release tagging:

- Merge to `main` -> CI runs on the merge commit.
- When CI completes successfully, `.github/workflows/deploy-cloud-run-main.yml` deploys Cloud Run from that same commit SHA.
- In parallel, `.github/workflows/auto-version-tag.yml` bumps and pushes the next semantic patch tag.
- The pushed `v*` tag triggers `.github/workflows/cd.yml` for GitHub Release artifact build/publish.
- You can also run the deploy workflow manually with `workflow_dispatch`.

This allows a standard branch -> PR -> merge -> deploy path while keeping tag-driven GitHub Releases in `.github/workflows/cd.yml`.