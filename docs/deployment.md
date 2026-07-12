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
