#!/usr/bin/env bash
set -euo pipefail

BASE_SHA="${1:-}"
HEAD_SHA="${2:-}"

if [[ -z "$BASE_SHA" || -z "$HEAD_SHA" ]]; then
  echo "Usage: $0 <base-sha> <head-sha>" >&2
  exit 2
fi

CHANGED_FILES="$(git diff --name-only "$BASE_SHA" "$HEAD_SHA")"

ARCH_SIGNIFICANT_REGEX='^(src/main/kotlin/com/personalagent/bertbot/(app|graph|agents|memory|llm|ingestion|config)/.+|build\.gradle\.kts|settings\.gradle\.kts)$'
CICD_SIGNIFICANT_REGEX='^\.github/workflows/.+\.(yml|yaml)$'

if echo "$CHANGED_FILES" | grep -E "$ARCH_SIGNIFICANT_REGEX" > /dev/null; then
  if ! echo "$CHANGED_FILES" | grep -Fx "docs/architecture.mmd" > /dev/null; then
    echo "Architecture-significant files changed, but docs/architecture.mmd was not updated."
    echo "Changed files:"
    echo "$CHANGED_FILES"
    exit 1
  fi
fi

if echo "$CHANGED_FILES" | grep -E "$CICD_SIGNIFICANT_REGEX" > /dev/null; then
  if ! echo "$CHANGED_FILES" | grep -Fx "docs/cicd-diagram.mmd" > /dev/null; then
    echo "CI/CD-significant workflow files changed, but docs/cicd-diagram.mmd was not updated."
    echo "Changed files:"
    echo "$CHANGED_FILES"
    exit 1
  fi
fi

echo "Changed-file guardrails passed."
