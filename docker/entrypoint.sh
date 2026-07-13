#!/usr/bin/env sh
set -eu

seed_google_workspace_oauth_credentials() {
  oauth_json_b64="${BERTBOT_GOOGLE_WORKSPACE_OAUTH_CREDENTIALS_JSON_B64:-}"
  token_b64="${BERTBOT_GOOGLE_WORKSPACE_TOKEN_B64:-}"
  master_key_b64="${BERTBOT_GOOGLE_WORKSPACE_MASTER_KEY_B64:-}"

  if [ -n "$oauth_json_b64" ]; then
    workspace_root="/opt/google-workspace-extension"
    transfer_json="$workspace_root/.tmp-oauth-bootstrap.json"

    umask 077
    mkdir -p "$workspace_root"

    if ! printf '%s' "$oauth_json_b64" | base64 -d > "$transfer_json" 2>/dev/null; then
      echo "Failed to decode BERTBOT_GOOGLE_WORKSPACE_OAUTH_CREDENTIALS_JSON_B64"
      exit 1
    fi

    if ! node -e "const fs=require('fs'); const {OAuthCredentialStorage}=require('/opt/google-workspace-extension/workspace-server/dist/auth-utils.js'); (async()=>{ const creds=JSON.parse(fs.readFileSync('$transfer_json','utf8')); await OAuthCredentialStorage.saveCredentials(creds); })().catch((err)=>{ console.error(err && err.message ? err.message : String(err)); process.exit(1); });"; then
      echo "Failed to persist Google Workspace OAuth credentials from bootstrap JSON"
      rm -f "$transfer_json"
      exit 1
    fi

    rm -f "$transfer_json"
    export GEMINI_CLI_WORKSPACE_FORCE_FILE_STORAGE="${GEMINI_CLI_WORKSPACE_FORCE_FILE_STORAGE:-true}"
    echo "Google Workspace OAuth credentials bootstrapped from JSON secret."
    return
  fi

  if [ -z "$token_b64" ] && [ -z "$master_key_b64" ]; then
    return
  fi

  if [ -z "$token_b64" ] || [ -z "$master_key_b64" ]; then
    echo "Google Workspace OAuth bootstrap skipped: both BERTBOT_GOOGLE_WORKSPACE_TOKEN_B64 and BERTBOT_GOOGLE_WORKSPACE_MASTER_KEY_B64 are required."
    return
  fi

  workspace_root="/opt/google-workspace-extension"
  token_path="$workspace_root/gemini-cli-workspace-token.json"
  master_key_path="$workspace_root/.gemini-cli-workspace-master-key"

  umask 077
  mkdir -p "$workspace_root"

  if ! printf '%s' "$token_b64" | base64 -d > "$token_path" 2>/dev/null; then
    echo "Failed to decode BERTBOT_GOOGLE_WORKSPACE_TOKEN_B64"
    exit 1
  fi

  if ! printf '%s' "$master_key_b64" | base64 -d > "$master_key_path" 2>/dev/null; then
    echo "Failed to decode BERTBOT_GOOGLE_WORKSPACE_MASTER_KEY_B64"
    exit 1
  fi

  chmod 600 "$token_path" "$master_key_path"
  export GEMINI_CLI_WORKSPACE_FORCE_FILE_STORAGE="${GEMINI_CLI_WORKSPACE_FORCE_FILE_STORAGE:-true}"
  echo "Google Workspace OAuth credentials loaded from secrets."
}

seed_google_workspace_oauth_credentials

MODE="${BERTBOT_RUN_MODE:-webhook}"

case "$MODE" in
  webhook)
    MAIN_CLASS="com.personalagent.bertbot.app.WebhookMainKt"
    ;;
  mcp)
    MAIN_CLASS="com.personalagent.bertbot.app.McpServerMainKt"
    ;;
  headless)
    MAIN_CLASS="com.personalagent.bertbot.app.HeadlessMainKt"
    ;;
  interactive)
    MAIN_CLASS="com.personalagent.bertbot.app.MainKt"
    ;;
  discord)
    MAIN_CLASS="com.personalagent.bertbot.app.DiscordBotMainKt"
    ;;
  *)
    echo "Unknown BERTBOT_RUN_MODE: $MODE"
    echo "Supported values: webhook, mcp, headless, interactive, discord"
    exit 1
    ;;
esac

exec java -cp "/opt/bertbot/lib/*" "$MAIN_CLASS" "$@"
