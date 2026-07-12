[CmdletBinding()]
param(
    [string]$LogPath = "logs/webhook_output.log"
)

$ErrorActionPreference = "Stop"

$workspaceRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $workspaceRoot

$resolvedLogPath = Join-Path $workspaceRoot $LogPath
$logDirectory = Split-Path $resolvedLogPath -Parent
if (-not (Test-Path $logDirectory)) {
    New-Item -ItemType Directory -Path $logDirectory | Out-Null
}

Write-Host ("Writing webhook server output to {0}" -f $resolvedLogPath)
& .\gradlew.bat runWebhookServer --no-daemon 2>&1 | Tee-Object -FilePath $resolvedLogPath
