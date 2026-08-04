#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VALIDATOR_SCRIPT="$SCRIPT_DIR/validate-custom-copilot-assets.sh"

if [[ ! -f "$VALIDATOR_SCRIPT" ]]; then
  echo "Validator script not found at $VALIDATOR_SCRIPT"
  exit 1
fi

TEMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TEMP_ROOT"' EXIT

create_valid_fixture() {
  local root="$1"

  mkdir -p "$root/.github/agents"
  mkdir -p "$root/.github/skills/code-review"
  mkdir -p "$root/.github/skills/repo-automation"
  mkdir -p "$root/.github/skills/test-orchestration"
  mkdir -p "$root/.github/skills/repo-ops"
  mkdir -p "$root/.github/skills/browser-automation"
  mkdir -p "$root/.github/skills/desktop-automation"

  cat > "$root/.github/agents/bertbot.agent.md" <<'EOF'
---
description: "BertBot"
name: BertBot
tools: [bertbot-backend/*]
user-invocable: true
---
EOF

  cat > "$root/.github/agents/repo-automation.agent.md" <<'EOF'
---
description: "Repo automation"
name: Repo Automation
tools: [bertbot-backend/*]
user-invocable: true
---
EOF

  cat > "$root/.github/agents/test-orchestration.agent.md" <<'EOF'
---
description: "Test orchestration"
name: Test Orchestration
tools: [bertbot-backend/*]
user-invocable: true
---
EOF

  cat > "$root/.github/agents/repo-ops.agent.md" <<'EOF'
---
description: "Repo ops"
name: Repo Ops
tools: [bertbot-backend/*]
user-invocable: true
---
EOF

  cat > "$root/.github/agents/browser-automation.agent.md" <<'EOF'
---
description: "Browser automation"
name: Browser Automation
tools: [bertbot-backend/*, playwright/*]
user-invocable: true
---
EOF

  cat > "$root/.github/agents/desktop-automation.agent.md" <<'EOF'
---
description: "Desktop automation"
name: Desktop Automation
tools: [bertbot-backend/*]
user-invocable: true
---
EOF

  cat > "$root/.github/skills/code-review/SKILL.md" <<'EOF'
---
name: code-review
description: "Review skill"
---
EOF

  cat > "$root/.github/skills/repo-automation/SKILL.md" <<'EOF'
---
name: repo-automation
description: "Repo automation skill"
---
EOF

  cat > "$root/.github/skills/test-orchestration/SKILL.md" <<'EOF'
---
name: test-orchestration
description: "Test orchestration skill"
---
EOF

  cat > "$root/.github/skills/repo-ops/SKILL.md" <<'EOF'
---
name: repo-ops
description: "Repo ops skill"
---
EOF

  cat > "$root/.github/skills/browser-automation/SKILL.md" <<'EOF'
---
name: browser-automation
description: "Browser automation skill"
---
EOF

  cat > "$root/.github/skills/desktop-automation/SKILL.md" <<'EOF'
---
name: desktop-automation
description: "Desktop automation skill"
---
EOF
}

assert_failure_contains() {
  local expected_text="$1"
  shift

  local output
  set +e
  output="$($VALIDATOR_SCRIPT "$@" 2>&1)"
  local status=$?
  set -e

  if [[ $status -eq 0 ]]; then
    echo "Expected failure but validator succeeded"
    exit 1
  fi

  if [[ "$output" != *"$expected_text"* ]]; then
    echo "Expected error output to contain: $expected_text"
    echo "Actual output:"
    echo "$output"
    exit 1
  fi
}

VALID_FIXTURE="$TEMP_ROOT/valid"
create_valid_fixture "$VALID_FIXTURE"

"$VALIDATOR_SCRIPT" "$VALID_FIXTURE" > /dev/null

MISSING_FILE_FIXTURE="$TEMP_ROOT/missing_file"
create_valid_fixture "$MISSING_FILE_FIXTURE"
rm -f "$MISSING_FILE_FIXTURE/.github/agents/repo-ops.agent.md"
assert_failure_contains "Missing required customization asset" "$MISSING_FILE_FIXTURE"

MISSING_AGENT_FIELD_FIXTURE="$TEMP_ROOT/missing_agent_field"
create_valid_fixture "$MISSING_AGENT_FIELD_FIXTURE"
cat > "$MISSING_AGENT_FIELD_FIXTURE/.github/agents/repo-automation.agent.md" <<'EOF'
---
description: "Repo automation"
name: Repo Automation
user-invocable: true
---
EOF
assert_failure_contains "Agent manifest missing 'tools:' field" "$MISSING_AGENT_FIELD_FIXTURE"

MISSING_SKILL_FIELD_FIXTURE="$TEMP_ROOT/missing_skill_field"
create_valid_fixture "$MISSING_SKILL_FIELD_FIXTURE"
cat > "$MISSING_SKILL_FIELD_FIXTURE/.github/skills/repo-ops/SKILL.md" <<'EOF'
---
name: repo-ops
---
EOF
assert_failure_contains "Skill manifest missing 'description:' field" "$MISSING_SKILL_FIELD_FIXTURE"

echo "validate-custom-copilot-assets regression tests passed."