#!/usr/bin/env bash
# check-env-drift.sh — verify key parity between .env.example and .env.compose.example.
#
# Usage: bash scripts/check-env-drift.sh
#
# Exits with 0 when the active (non-commented) key sets match, or 1 when any key
# appears in one template but not the other.  Values are intentionally allowed to
# differ (e.g. BERTBOT_STATE_STORE or BERTBOT_OLLAMA_BASE_URL) to reflect the
# different defaults appropriate for local vs compose deployments.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCAL="$ROOT/.env.example"
COMPOSE="$ROOT/.env.compose.example"

extract_keys() {
    grep -E '^[A-Z_]+=' "$1" | sed 's/=.*//' | sort
}

LOCAL_KEYS=$(extract_keys "$LOCAL")
COMPOSE_KEYS=$(extract_keys "$COMPOSE")

ONLY_LOCAL=$(comm -23 <(echo "$LOCAL_KEYS") <(echo "$COMPOSE_KEYS"))
ONLY_COMPOSE=$(comm -13 <(echo "$LOCAL_KEYS") <(echo "$COMPOSE_KEYS"))

FAIL=0

if [[ -n "$ONLY_LOCAL" ]]; then
    echo "Keys in .env.example but missing from .env.compose.example:"
    echo "$ONLY_LOCAL" | sed 's/^/  /'
    FAIL=1
fi

if [[ -n "$ONLY_COMPOSE" ]]; then
    echo "Keys in .env.compose.example but missing from .env.example:"
    echo "$ONLY_COMPOSE" | sed 's/^/  /'
    FAIL=1
fi

if [[ $FAIL -eq 0 ]]; then
    LOCAL_COUNT=$(echo "$LOCAL_KEYS" | wc -l | tr -d ' ')
    echo "No key drift detected. Both templates define ${LOCAL_COUNT} active keys."
fi

exit $FAIL
