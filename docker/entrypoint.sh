#!/usr/bin/env sh
set -eu

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
