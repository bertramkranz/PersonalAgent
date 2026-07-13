# GitHub Automation

This repository includes GitHub workflow automation for merge guardrails, secret scanning, and Copilot-assisted review workflows.

## Workflow Overview

- `.github/workflows/bootstrap-repo-guardrails.yml`: ensures repository guardrails such as the `auto-merge` label exist.
- `.github/workflows/merge-generated-prs-on-green.yml`: approves and enables GitHub native auto-merge for eligible pull requests when required checks pass.
- `.github/workflows/deploy-cloud-run-main.yml`: builds and deploys the webhook service to Cloud Run after CI succeeds on `main` (also supports manual dispatch).
- `.github/workflows/secret-scan.yml`: scans pushes and pull requests for leaked secrets and uploads SARIF findings.

## Generated PR Merge Automation

The merge workflow is designed for trusted generated pull requests.

- It uses the `auto-merge` label as the main opt-in signal.
- It re-runs action-required checks when needed.
- It auto-approves when the configured automation identity is allowed to do so.
- It enables GitHub native auto-merge after required checks are green.
- It supports `AUTOMATION_PAT`, with fallback to `github.token` when applicable.

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

To trigger a release after publishing:

```bash
git tag v1.0.0
git push origin v1.0.0
```

## Main Branch Deployment Trigger

Cloud deployment is now decoupled from release tagging:

- Merge to `main` -> CI runs on the merge commit.
- When CI completes successfully, `.github/workflows/deploy-cloud-run-main.yml` deploys Cloud Run from that same commit SHA.
- You can also run the deploy workflow manually with `workflow_dispatch`.

This allows a standard branch -> PR -> merge -> deploy path while keeping tag-driven GitHub Releases in `.github/workflows/cd.yml`.