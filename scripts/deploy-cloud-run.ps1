[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectId,

    [Parameter(Mandatory = $true)]
    [string]$Region,

    [Parameter(Mandatory = $true)]
    [string]$ArtifactRegistryRepo,

    [Parameter(Mandatory = $true)]
    [string]$ImageTag,

    [Parameter(Mandatory = $true)]
    [string]$CloudSqlInstance,

    [string]$ServiceName = "bertbot-webhook",
    [string]$DatabaseName = "bertbot",
    [string]$DatabaseUser = "bertbot",
    [string]$ServiceAccount,
    [string]$AiApiKeySecret = "bertbot-ai-api-key",
    [string]$TelegramSecretTokenSecret,
    [string]$SlackSigningSecret,
    [string]$WhatsAppAppSecret,
    [string]$WhatsAppVerifyToken,
    [switch]$AllowUnauthenticated
)

$ErrorActionPreference = "Stop"

function Invoke-NativeCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed: $FilePath $($Arguments -join ' ')"
    }
}

$workspaceRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $workspaceRoot

$imageUri = "${Region}-docker.pkg.dev/${ProjectId}/${ArtifactRegistryRepo}/${ServiceName}:${ImageTag}"
$cloudSqlConnection = "$ProjectId`:$Region`:$CloudSqlInstance"
$jdbcUrl = "jdbc:postgresql:///" + $DatabaseName + "?cloudSqlInstance=" + $cloudSqlConnection + "&socketFactory=com.google.cloud.sql.postgres.SocketFactory"

Write-Host "Using image: $imageUri"
Write-Host "Cloud SQL instance: $cloudSqlConnection"

Invoke-NativeCommand -FilePath "gcloud" -Arguments @("config", "set", "project", $ProjectId)
Invoke-NativeCommand -FilePath "gcloud" -Arguments @("auth", "configure-docker", "${Region}-docker.pkg.dev", "--quiet")

$deployArgs = @(
    "run", "deploy", $ServiceName,
    "--image", $imageUri,
    "--region", $Region,
    "--platform", "managed",
    "--execution-environment", "gen2",
    "--port", "8088",
    "--cpu", "1",
    "--memory", "1Gi",
    "--concurrency", "1",
    "--min-instances", "1",
    "--add-cloudsql-instances", $cloudSqlConnection,
    "--set-env-vars", "BERTBOT_RUN_MODE=webhook,BERTBOT_STATE_STORE=postgres,BERTBOT_WEBHOOK_HOST=0.0.0.0,BERTBOT_WEBHOOK_PORT=8088,BERTBOT_STATE_JDBC_URL=$jdbcUrl,BERTBOT_STATE_JDBC_USER=$DatabaseUser"
)

if ($AllowUnauthenticated) {
    $deployArgs += "--allow-unauthenticated"
}

if ($ServiceAccount) {
    $deployArgs += @("--service-account", $ServiceAccount)
}

$secretMappings = @()
$secretMappings += "BERTBOT_AI_API_KEY=$AiApiKeySecret:latest"

if ($TelegramSecretTokenSecret) {
    $secretMappings += "BERTBOT_TELEGRAM_SECRET_TOKEN=$TelegramSecretTokenSecret:latest"
}

if ($SlackSigningSecret) {
    $secretMappings += "BERTBOT_SLACK_SIGNING_SECRET=$SlackSigningSecret:latest"
}

if ($WhatsAppAppSecret) {
    $secretMappings += "BERTBOT_WHATSAPP_APP_SECRET=$WhatsAppAppSecret:latest"
}

if ($WhatsAppVerifyToken) {
    $secretMappings += "BERTBOT_WHATSAPP_VERIFY_TOKEN=$WhatsAppVerifyToken:latest"
}

if ($secretMappings.Count -gt 0) {
    $deployArgs += @("--set-secrets", ($secretMappings -join ","))
}

Invoke-NativeCommand -FilePath "gcloud" -Arguments $deployArgs

Write-Host "Deployment completed."
Write-Host "If the Cloud SQL database/user do not exist yet, create them before using the service."
