# Deployment

This repository supports two deployment styles:

- Cloud Run plus Cloud SQL for the hosted public webhook service.
- Docker Compose for self-hosted local or small-scale runtime stacks.

See [configuration.md](configuration.md) for environment-variable details and [run-modes.md](run-modes.md) for runtime-mode behavior.

## Recommended Production Shape

- Cloud Run for `BERTBOT_RUN_MODE=webhook`
- Cloud SQL for PostgreSQL for state, memory, and profile persistence
- Secret Manager for `BERTBOT_AI_API_KEY` and webhook verification secrets
- `concurrency=1` and `min-instances=1` as a solo-dev-friendly baseline

## Cloud Run Deployment

Use [../scripts/deploy-cloud-run.ps1](../scripts/deploy-cloud-run.ps1) to deploy the public webhook service.

For automated deploys after a branch is merged to `main`, use [../.github/workflows/deploy-cloud-run-main.yml](../.github/workflows/deploy-cloud-run-main.yml).

For a one-command idempotent setup (provision resources, optionally capture secrets, build image, and deploy), use [../scripts/bootstrap-cloud-run.ps1](../scripts/bootstrap-cloud-run.ps1).

Example using this workspace's detected project and region:

```powershell
.\scripts\bootstrap-cloud-run.ps1 `
  -ProjectId "personal-agent-502221" `
  -Region "europe-west2" `
  -ArtifactRegistryRepo "bertbot" `
  -CloudSqlInstance "bertbot-postgres" `
  -CreateSecrets `
  -AllowUnauthenticated
```

Example:

```powershell
.\scripts\deploy-cloud-run.ps1 `
  -ProjectId "YOUR_PROJECT_ID" `
  -Region "YOUR_REGION" `
  -ArtifactRegistryRepo "YOUR_ARTIFACT_REPO" `
  -ImageTag "latest" `
  -CloudSqlInstance "YOUR_CLOUDSQL_INSTANCE" `
  -AllowUnauthenticated
```

The script expects the image to already exist in Artifact Registry. It deploys with:

- `BERTBOT_RUN_MODE=webhook`
- `BERTBOT_STATE_STORE=postgres`
- `BERTBOT_WEBHOOK_HOST=0.0.0.0`
- `BERTBOT_WEBHOOK_PORT=8088`
- `BERTBOT_STATE_JDBC_URL=jdbc:postgresql:///bertbot?cloudSqlInstance=PROJECT:REGION:INSTANCE&socketFactory=com.google.cloud.sql.postgres.SocketFactory`

### GitHub Actions Auto Deploy (Main -> Cloud Run)

The workflow [../.github/workflows/deploy-cloud-run-main.yml](../.github/workflows/deploy-cloud-run-main.yml) provides a continuous deployment path for production webhook hosting:

- Trigger 1: automatically after [../.github/workflows/ci.yml](../.github/workflows/ci.yml) completes successfully on `main`.
- Trigger 2: manual `workflow_dispatch` for controlled redeploys.
- Build step: builds and pushes image tagged with the merge commit SHA.
- Deploy step: updates Cloud Run with hardened webhook defaults.

Default deploy settings in workflow:

- `BERTBOT_WEBHOOK_REQUIRE_SIGNATURES=true`
- `BERTBOT_INGESTION_REQUIRE_APPROVAL=false`
- Cloud SQL Postgres socket-factory JDBC wiring
- Secret Manager mappings for AI key, DB password, and Telegram secret token

Required GitHub repository secrets:

- `GCP_WORKLOAD_IDENTITY_PROVIDER`
- `GCP_DEPLOYER_SERVICE_ACCOUNT`

Required GitHub repository variables:

- `GCP_PROJECT_ID`
- `GCP_REGION`
- `GCP_ARTIFACT_REGISTRY_REPO`
- `GCP_CLOUDSQL_INSTANCE`

Optional GitHub repository variables:

- `CLOUD_RUN_SERVICE` (default `bertbot-webhook`)
- `BERTBOT_DB_NAME` (default `bertbot`)
- `BERTBOT_DB_USER` (default `bertbot`)
- `AI_API_KEY_SECRET_NAME` (default `bertbot-ai-api-key`)
- `DB_PASSWORD_SECRET_NAME` (default `bertbot-db-password`)
- `TELEGRAM_SECRET_TOKEN_SECRET_NAME` (default `bertbot-telegram-secret-token`)
- `BERTBOT_GOOGLE_WORKSPACE_ENABLED` (default `true`)
- `GOOGLE_WORKSPACE_TOKEN_B64_SECRET_NAME` (default empty, recommended for calendar/drive auth on Cloud Run)
- `GOOGLE_WORKSPACE_MASTER_KEY_B64_SECRET_NAME` (default empty, must be paired with token secret)

The workflow currently uses these built-in defaults for optional integration wiring:

- `SLACK_SIGNING_SECRET_NAME=bertbot-slack-signing-secret`
- `WHATSAPP_APP_SECRET_NAME=bertbot-whatsapp-app-secret`
- `WHATSAPP_VERIFY_TOKEN_SECRET_NAME=bertbot-whatsapp-verify-token`
- `CLOUD_RUN_RUNTIME_SERVICE_ACCOUNT` unset
- `CLOUD_RUN_ALLOW_UNAUTHENTICATED=true`

If you need different secret names or a runtime service account, update [../.github/workflows/deploy-cloud-run-main.yml](../.github/workflows/deploy-cloud-run-main.yml) directly or add a follow-up workflow input/variable path.

### Google Workspace OAuth In Cloud Run

Google Workspace tools need OAuth credentials even when the MCP server itself is running. In Cloud Run, browser-based login is unavailable, so you must preload OAuth token storage files via Secret Manager.

1. Generate credentials once in an interactive environment:

```bash
cd /opt/google-workspace-extension/workspace-server
node dist/headless-login.js
```

2. Base64-encode both generated files:

- `/opt/google-workspace-extension/gemini-cli-workspace-token.json`
- `/opt/google-workspace-extension/.gemini-cli-workspace-master-key`

3. Store each base64 output in Secret Manager (for example `bertbot-google-workspace-token-b64` and `bertbot-google-workspace-master-key-b64`).
4. Wire secret names into deploy paths:

- Manual script: pass `-GoogleWorkspaceTokenB64Secret` and `-GoogleWorkspaceMasterKeyB64Secret` to [../scripts/deploy-cloud-run.ps1](../scripts/deploy-cloud-run.ps1) or [../scripts/bootstrap-cloud-run.ps1](../scripts/bootstrap-cloud-run.ps1).
- GitHub Actions: set repository variables `GOOGLE_WORKSPACE_TOKEN_B64_SECRET_NAME` and `GOOGLE_WORKSPACE_MASTER_KEY_B64_SECRET_NAME`.

At container startup, [../docker/entrypoint.sh](../docker/entrypoint.sh) decodes these secrets to the expected workspace extension file paths and forces file-based token storage for headless operation.

The deployer identity (configured in `GCP_DEPLOYER_SERVICE_ACCOUNT`) needs IAM permissions for Artifact Registry push, Cloud Run deploy/update, and service usage needed by the deployment command. The Cloud Run runtime service account needs Secret Manager accessor and Cloud SQL client permissions for runtime access.

### Cloud SQL Setup

Create the database and user before the first deploy if they do not already exist.

```bash
gcloud sql databases create bertbot --instance=YOUR_CLOUDSQL_INSTANCE
gcloud sql users create bertbot --instance=YOUR_CLOUDSQL_INSTANCE --password=YOUR_PASSWORD
```

### Hosted Deployment Notes

- The app falls back to Cloud Run's `PORT` environment variable if `BERTBOT_WEBHOOK_PORT` is unset.
- Keep file-backed persistence for local development only.
- Do not deploy the MCP stdio server or Discord gateway mode to Cloud Run.

## Docker Compose

The self-hosted container baseline is built around [../Dockerfile](../Dockerfile), [../docker-compose.yml](../docker-compose.yml), and [../docker/entrypoint.sh](../docker/entrypoint.sh).

### Compose Files And Roles

- [../Dockerfile](../Dockerfile): multi-stage build and runtime image
- [../docker-compose.yml](../docker-compose.yml): webhook app, optional MCP app, PostgreSQL, and optional Ollama services
- [../docker/entrypoint.sh](../docker/entrypoint.sh): runtime mode switch for `webhook`, `mcp`, `headless`, `interactive`, and `discord`
- [../.env.compose.example](../.env.compose.example): compose-oriented configuration template

### Quick Start

1. Copy [../.env.compose.example](../.env.compose.example) to `.env.compose` and set the required provider credentials.
2. Start the stack:

```bash
docker compose up --build -d
```

3. Inspect logs:

```bash
docker compose logs -f bertbot
```

4. Verify health:

```bash
curl http://localhost:8088/health
```

5. Stop the stack:

```bash
docker compose down
```

If you also run the MCP profile, inspect those logs with:

```bash
docker compose logs -f bertbot-mcp
```

### Runtime Defaults

- App bind address: `0.0.0.0:8088`
- Default host port mapping: `8088:8088`
- Default container run mode: `BERTBOT_RUN_MODE=webhook`
- Optional MCP profile service uses `BERTBOT_RUN_MODE=mcp`

### Persistence Policy

Containerized runtimes should use PostgreSQL persistence.

- Expected backend: `BERTBOT_STATE_STORE=postgres`
- Example JDBC URL: `BERTBOT_STATE_JDBC_URL=jdbc:postgresql://postgres:5432/bertbot`

Local Gradle runs and workspace MCP sessions can keep the default file backend for low-friction development.

There is no automatic fallback from PostgreSQL to file persistence when `BERTBOT_STATE_STORE` is set to `postgres`.

### Optional Ollama Profile

Bring up the optional Ollama service with:

```bash
docker compose --profile ollama up --build -d
```

When using Ollama in containers, set `BERTBOT_AI_PROVIDER=ollama` and choose a model that already exists in the Ollama instance.

### Local SonarQube Community (Docker Compose)

Use [../docker-compose.sonarqube.yml](../docker-compose.sonarqube.yml) to run SonarQube Community Edition locally with a dedicated PostgreSQL backend.

Start SonarQube:

```bash
docker compose -f docker-compose.sonarqube.yml up -d
```

Open the UI:

```text
http://localhost:9000
```

Run local analysis against this SonarQube instance:

```bash
export SONAR_HOST_URL=http://localhost:9000
export SONAR_TOKEN=<your-sonar-token>
./gradlew --no-daemon check sonar
```

If you run this from PowerShell, use:

```powershell
$env:SONAR_HOST_URL = "http://localhost:9000"
$env:SONAR_TOKEN = "<your-sonar-token>"
.\gradlew.bat --no-daemon check sonar
```

Stop the local SonarQube stack:

```bash
docker compose -f docker-compose.sonarqube.yml down
```

To remove SonarQube data volumes as well:

```bash
docker compose -f docker-compose.sonarqube.yml down -v
```

You can also use the helper script [../scripts/sonarqube-local.ps1](../scripts/sonarqube-local.ps1):

```powershell
.\scripts\sonarqube-local.ps1 -Action start
.\scripts\sonarqube-local.ps1 -Action status
.\scripts\sonarqube-local.ps1 -Action analyze -SonarToken "<your-sonar-token>"
.\scripts\sonarqube-local.ps1 -Action stop
```

### Webhook And MCP Service Variants

Start webhook mode explicitly:

```bash
docker compose up --build -d bertbot postgres
```

Start MCP mode as a profile service:

```bash
docker compose --profile mcp up --build -d bertbot-mcp postgres
```

Run the MCP service as an attached stdio session:

```bash
docker compose --profile mcp run --rm bertbot-mcp
```

### Switching Modes In Containers

Set `BERTBOT_RUN_MODE` to one of:

- `webhook`
- `mcp`
- `headless`
- `interactive`
- `discord`
