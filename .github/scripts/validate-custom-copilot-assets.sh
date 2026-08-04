#!/usr/bin/env bash
set -euo pipefail

WORKSPACE_ROOT="${1:-.}"

REQUIRED_AGENT_FILES=(
  ".github/agents/bertbot.agent.md"
  ".github/agents/repo-automation.agent.md"
  ".github/agents/test-orchestration.agent.md"
  ".github/agents/repo-ops.agent.md"
  ".github/agents/browser-automation.agent.md"
  ".github/agents/desktop-automation.agent.md"
)

REQUIRED_SKILL_FILES=(
  ".github/skills/code-review/SKILL.md"
  ".github/skills/repo-automation/SKILL.md"
  ".github/skills/test-orchestration/SKILL.md"
  ".github/skills/repo-ops/SKILL.md"
  ".github/skills/browser-automation/SKILL.md"
  ".github/skills/desktop-automation/SKILL.md"
)

check_file_exists() {
  local relative_path="$1"
  local file_path="$WORKSPACE_ROOT/$relative_path"
  if [[ ! -f "$file_path" ]]; then
    echo "Missing required customization asset: $relative_path"
    exit 1
  fi
}

check_agent_front_matter() {
  local relative_path="$1"
  local file_path="$WORKSPACE_ROOT/$relative_path"
  if ! grep -q '^name:' "$file_path"; then
    echo "Agent manifest missing 'name:' field: $relative_path"
    exit 1
  fi
  if ! grep -q '^description:' "$file_path"; then
    echo "Agent manifest missing 'description:' field: $relative_path"
    exit 1
  fi
  if ! grep -q '^tools:' "$file_path"; then
    echo "Agent manifest missing 'tools:' field: $relative_path"
    exit 1
  fi
  if ! grep -q '^user-invocable:' "$file_path"; then
    echo "Agent manifest missing 'user-invocable:' field: $relative_path"
    exit 1
  fi
}

check_skill_front_matter() {
  local relative_path="$1"
  local file_path="$WORKSPACE_ROOT/$relative_path"
  if ! grep -q '^name:' "$file_path"; then
    echo "Skill manifest missing 'name:' field: $relative_path"
    exit 1
  fi
  if ! grep -q '^description:' "$file_path"; then
    echo "Skill manifest missing 'description:' field: $relative_path"
    exit 1
  fi
}

for asset in "${REQUIRED_AGENT_FILES[@]}"; do
  check_file_exists "$asset"
  check_agent_front_matter "$asset"
done

for asset in "${REQUIRED_SKILL_FILES[@]}"; do
  check_file_exists "$asset"
  check_skill_front_matter "$asset"
done

echo "Custom Copilot asset validation passed."