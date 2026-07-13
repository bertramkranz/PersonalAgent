[CmdletBinding()]
param(
    [ValidateSet("start", "stop", "status", "logs", "analyze", "cleanup")]
    [string]$Action = "status",

    [string]$ComposeFile = "docker-compose.sonarqube.yml",
    [string]$SonarHostUrl = "http://localhost:9000",
    [string]$SonarToken
)

$ErrorActionPreference = "Stop"

function Invoke-NativeCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [switch]$IgnoreExitCode
    )

    & $FilePath @Arguments
    if (-not $IgnoreExitCode -and $LASTEXITCODE -ne 0) {
        throw "Command failed: $FilePath $($Arguments -join ' ')"
    }
}

$workspaceRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $workspaceRoot

if (-not (Test-Path -Path $ComposeFile)) {
    throw "Compose file not found: $ComposeFile"
}

switch ($Action) {
    "start" {
        Invoke-NativeCommand -FilePath "docker" -Arguments @("compose", "-f", $ComposeFile, "up", "-d")
        Write-Host "SonarQube started. Open $SonarHostUrl"
    }

    "stop" {
        Invoke-NativeCommand -FilePath "docker" -Arguments @("compose", "-f", $ComposeFile, "down")
        Write-Host "SonarQube stopped."
    }

    "cleanup" {
        Invoke-NativeCommand -FilePath "docker" -Arguments @("compose", "-f", $ComposeFile, "down", "-v")
        Write-Host "SonarQube stopped and volumes removed."
    }

    "status" {
        Invoke-NativeCommand -FilePath "docker" -Arguments @("compose", "-f", $ComposeFile, "ps")
    }

    "logs" {
        Invoke-NativeCommand -FilePath "docker" -Arguments @("compose", "-f", $ComposeFile, "logs", "-f", "sonarqube")
    }

    "analyze" {
        if ([string]::IsNullOrWhiteSpace($SonarToken)) {
            throw "-SonarToken is required for analyze action."
        }

        $env:SONAR_HOST_URL = $SonarHostUrl
        $env:SONAR_TOKEN = $SonarToken

        Invoke-NativeCommand -FilePath ".\\gradlew.bat" -Arguments @("--no-daemon", "check", "sonar")
    }

    default {
        throw "Unsupported action: $Action"
    }
}